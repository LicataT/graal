/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.hotspot.management.libgraal;

import static org.graalvm.libgraal.jni.JNIUtil.createString;
import static org.graalvm.libgraal.jni.JNIUtil.getBinaryName;

import java.lang.reflect.Method;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import javax.management.DynamicMBean;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntime;
import org.graalvm.compiler.hotspot.management.JMXToLibGraalCalls;
import org.graalvm.compiler.hotspot.management.LibGraalMBean;
import org.graalvm.compiler.hotspot.management.JMXFromLibGraalEntryPoints;
import org.graalvm.libgraal.jni.JNI;
import org.graalvm.libgraal.jni.JNIUtil;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;

class MBeanProxy<T extends DynamicMBean> {

    private static final Method getCurrentJavaThreadMethod;
    static {
        Method m;
        try {
            m = HotSpotJVMCIRuntime.class.getMethod("getCurrentJavaThread");
        } catch (NoSuchMethodException e) {
            m = null;
        }
        getCurrentJavaThreadMethod = m;
    }

    // Classes defined in HotSpot heap by JNI.
    private static final ClassData HS_BEAN_CLASS = ClassData.create(LibGraalMBean.class);
    private static final ClassData HS_BEAN_FACTORY_CLASS = ClassData.create(LibGraalMBean.Factory.class);
    private static final ClassData HS_CALLS_CLASS = ClassData.create(JMXToLibGraalCalls.class);
    private static final ClassData HS_PUSHBACK_ITER_CLASS = ClassData.create(LibGraalMBean.PushBackIterator.class);
    private static final ClassData HS_ENTRYPOINTS_CLASS = ClassData.create(JMXFromLibGraalEntryPoints.class);

    /**
     * Pending MBeans registrations on HotSpot side.
     *
     * Access is synchronized on {@code MBeanProxy.class}.
     */
    private static Queue<MBeanProxy<?>> registrations = new ArrayDeque<>();

    // JNI Globals
    private static JNI.JClass fromLibGraalEntryPoints;

    /**
     * Offset of the {@code _jni_environment} field in {@code JavaThread}.
     */
    private static volatile long jniEnvOffset;

    private static LibGraalMemoryPoolMBean memPoolBean;

    /**
     * MBeans to un-register on HotSpot side when isolate is closed. The first addition to the list
     * registers a shutdown hook to do the un-registration. Upon unregistration, this field is set
     * to {@code null}.
     *
     * Access is synchronized on {@code MBeanProxy.class}.
     */
    private static List<MBeanProxy<?>> mBeansToUnregisterOnIsolateClose = new LinkedList<>();

    /**
     * Lifecycle state.
     *
     * Access is synchronized on {@code MBeanProxy.class}.
     *
     * @see State
     */
    private static State state = State.ACTIVE;

    /**
     * The MBean instance.
     */
    private T bean;

    /**
     * The name of the MBean.
     */
    private String name;

    /**
     * JMX Object name.
     */
    private ObjectName objName;

    /**
     * Flag for pending registration.
     */
    private volatile boolean needsRegistration = true;

    /**
     * Creates a new uninitialized {@link MBeanProxy}. The
     * {@link MBeanProxy#initialize(javax.management.DynamicMBean, java.lang.String, javax.management.ObjectName)}
     * must be called before the instance is used.
     */
    MBeanProxy() {
    }

    /**
     * Creates a new {@link MBeanProxy} initialized by given {@code mbean}.
     */
    MBeanProxy(T mbean, String strName) throws MalformedObjectNameException {
        String nameWithIsolate = String.format("%s_%x", strName, CurrentIsolate.getIsolate().rawValue());
        initialize(mbean, nameWithIsolate, new ObjectName(nameWithIsolate));
    }

    void initialize(T mbean, String strName, ObjectName objectName) {
        Objects.requireNonNull(mbean);
        Objects.requireNonNull(strName);
        Objects.requireNonNull(objectName);
        if (this.bean != null) {
            throw new IllegalStateException("Already initialized.");
        }
        assert this.name == null;
        assert this.objName == null;
        this.bean = mbean;
        this.name = strName;
        this.objName = objectName;
    }

    /**
     * Returns the MBean used for delegation from HotSpot heap.
     */
    T getBean() {
        return bean;
    }

    /**
     * Notification about finished registration in HotSpot heap.
     */
    void finishRegistration() {
        needsRegistration = false;
    }

    /**
     * Returns the name which should be used to register this MBean.
     */
    String getName() {
        return name;
    }

    ObjectName poll() {
        LibGraalMemoryPoolMBean memPool = memPoolBean;
        if (memPool != null) {
            memPool.update();
        }
        if (bean == null || needsRegistration) {
            return null;
        }
        return objName;
    }

    static boolean initializeJNI(GraalHotSpotVMConfig config, HotSpotGraalRuntime runtime) {
        if (getCurrentJavaThreadMethod == null) {
            return false;
        }
        if (jniEnvOffset == 0) {
            synchronized (MBeanProxy.class) {
                if (jniEnvOffset == 0) {
                    if (config.jniEnvironmentOffset == Integer.MIN_VALUE) {
                        // Old unsupported JVMCI version.
                        return false;
                    }
                    memPoolBean = new LibGraalMemoryPoolMBean();
                    jniEnvOffset = config.jniEnvironmentOffset;
                    defineClassesInHotSpot(getCurrentJNIEnv());
                    try {
                        MBeanProxy<?> memPoolMBean = new MBeanProxy<>(memPoolBean, LibGraalMemoryPoolMBean.NAME);
                        enqueueForRegistration(memPoolMBean, runtime);
                    } catch (MalformedObjectNameException mon) {
                        throw new AssertionError("Invlid object name.", mon);
                    }
                }
            }
        }
        return true;
    }

    static JNI.JClass getHotSpotEntryPoints() {
        return fromLibGraalEntryPoints;
    }

    /**
     * Computes {@code JNIEnv} for a current {@code JavaThread}.
     */
    static JNI.JNIEnv getCurrentJNIEnv() {
        if (jniEnvOffset == 0) {
            throw new IllegalStateException("JniEnvOffset is not yet initialized.");
        }
        if (getCurrentJavaThreadMethod == null) {
            throw new IllegalStateException("CurrentJavaThread not supported by JVMCI.");
        }
        try {
            long currentJavaThreadAddr = (Long) getCurrentJavaThreadMethod.invoke(HotSpotJVMCIRuntime.runtime());
            return WordFactory.pointer(currentJavaThreadAddr + jniEnvOffset);
        } catch (ReflectiveOperationException reflectiveException) {
            throw new RuntimeException("Failed to invoke HotSpotJVMCIRuntime::getCurrentJavaThread", reflectiveException);
        }
    }

    /**
     * Removes the pending registrations.
     *
     * @return the pending registrations
     */
    static synchronized List<MBeanProxy<?>> drainRegistrations() {
        if (state != State.ACTIVE || registrations.isEmpty()) {
            return Collections.emptyList();
        } else {
            List<MBeanProxy<?>> res = new ArrayList<>(registrations);
            registrations.clear();
            return res;
        }
    }

    /**
     * Registers a given {@link LibGraalHotSpotGraalManagement} instance into pending registrations and
     * notifies the worker in HotSpot heap.
     *
     * @return the {@code instance} if successfully registered or {@code null} when the registration
     *         in not accepted because the isolate is closing
     */
    static <T extends MBeanProxy<?>> T enqueueForRegistrationAndNotify(T instance, HotSpotGraalRuntime runtime) {
        T res = enqueueForRegistration(instance, runtime);
        if (res != null) {
            signalRegistrationRequest();
        }
        return res;
    }

    /**
     * Registers a given {@link HotSpotGraalManagement} instance into pending registrations.
     *
     * @return the {@code instance} if successfully registered or {@code null} when the registration
     *         in not accepted because the isolate is closing
     */
    private static synchronized <T extends MBeanProxy<?>> T enqueueForRegistration(T instance, HotSpotGraalRuntime runtime) {
        if (state != State.ACTIVE) {
            return null;
        }
        registrations.add(instance);
        if (mBeansToUnregisterOnIsolateClose.isEmpty()) {
            runtime.addShutdownHook(new OnShutDown());
        }
        mBeansToUnregisterOnIsolateClose.add(instance);
        return instance;
    }

    /**
     * Uses JNI to define the classes in HotSpot heap.
     */
    private static void defineClassesInHotSpot(JNI.JNIEnv env) {
        JNI.JObject classLoader = JMXFromLibGraalCalls.getJVMCIClassLoader(env);
        JNI.JClass toLibGraalCalls = defineClassInHotSpot(env, classLoader, HS_CALLS_CLASS);
        if (toLibGraalCalls.isNonNull()) {
            try {
                registerNatives(env, classLoader, toLibGraalCalls);
            } finally {
                notifyNativesRegistered(env, toLibGraalCalls);
            }
        } else {
            toLibGraalCalls = findClassInHotSpot(env, classLoader, HS_CALLS_CLASS.binaryName, true);
            waitForRegisterNatives(env, toLibGraalCalls);
        }
        JNI.JClass entryPoints = findOrDefineClassInHotSpot(env, classLoader, HS_ENTRYPOINTS_CLASS);
        findOrDefineClassInHotSpot(env, classLoader, HS_BEAN_CLASS);
        findOrDefineClassInHotSpot(env, classLoader, HS_BEAN_FACTORY_CLASS);
        findOrDefineClassInHotSpot(env, classLoader, HS_PUSHBACK_ITER_CLASS);
        fromLibGraalEntryPoints = JNIUtil.NewGlobalRef(env, entryPoints, "Class<" + HS_ENTRYPOINTS_CLASS.binaryName + ">");
    }

    private static JNI.JClass findOrDefineClassInHotSpot(JNI.JNIEnv env, JNI.JObject classLoader, ClassData classData) {
        JNI.JClass res = findClassInHotSpot(env, classLoader, classData.binaryName, false);
        if (res.isNonNull()) {
            return res;
        }
        res = defineClassInHotSpot(env, classLoader, classData);
        if (res.isNonNull()) {
            return res;
        }
        return findClassInHotSpot(env, classLoader, classData.binaryName, true);
    }

    /**
     * Finds a class in HotSpot heap using JNI.
     *
     * @param env the {@code JNIEnv}
     * @param classLoader the class loader to define class in.
     * @param className the class name
     * @param required if {@code true} the {@link InternalError} is thrown when the class is not
     *            found, if {@code false} the {@code NULL pointer} is returned when the class is not
     *            found.
     */
    private static JNI.JClass findClassInHotSpot(JNI.JNIEnv env, JNI.JObject classLoader, String className, boolean required) {
        Class<? extends Throwable> allowedException = null;
        try {
            if (classLoader.isNonNull()) {
                allowedException = required ? null : ClassNotFoundException.class;
                return JMXFromLibGraalCalls.findClass(env, classLoader, className);
            } else {
                allowedException = required ? null : NoClassDefFoundError.class;
                return JMXFromLibGraalCalls.findClass(env, className);
            }
        } finally {
            if (allowedException != null) {
                checkException(env, "Failed to load " + className, allowedException);
            } else {
                checkException(env, "Failed to load " + className);
            }
        }
    }

    /**
     * Checks and clears JNI pending exception. If the pending exception type is not allowed by
     * {@code allowedExceptions} it throws an {@link InternalError}.
     */
    @SafeVarargs
    static void checkException(JNI.JNIEnv env, String message, Class<? extends Throwable>... allowedExceptions) {
        if (JNIUtil.ExceptionCheck(env)) {
            try {
                JNI.JThrowable exception = JNIUtil.ExceptionOccurred(env);
                JNIUtil.ExceptionClear(env);
                JNI.JClass exceptionClass = JNIUtil.GetObjectClass(env, exception);
                boolean allowed = false;
                for (Class<? extends Throwable> allowedException : allowedExceptions) {
                    JNI.JClass allowedExceptionClass = JMXFromLibGraalCalls.findClass(env, getBinaryName(allowedException.getName()));
                    if (allowedExceptionClass.isNonNull() && JNIUtil.IsSameObject(env, exceptionClass, allowedExceptionClass)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    InternalError error = new InternalError(String.format("%s due to %s:%s.",
                                    message,
                                    createString(env, JMXFromLibGraalCalls.getClassName(env, exceptionClass)),
                                    createString(env, JMXFromLibGraalCalls.getExceptionMessage(env, exception))));
                    throw error;
                }
            } finally {
                JNIUtil.ExceptionClear(env);
            }
        }
    }

    /**
     * Defines a class in HotSpot heap using JNI.
     *
     * @param env the {@code JNIEnv}
     * @param classLoader the class loader to define class in.
     * @param classData the class to define in HotSpot
     * @return the defined class
     */
    private static JNI.JClass defineClassInHotSpot(JNI.JNIEnv env, JNI.JObject classLoader, ClassData classData) {
        CCharPointer classDataPointer = UnmanagedMemory.malloc(classData.byteCode.length);
        ByteBuffer buffer = CTypeConversion.asByteBuffer(classDataPointer, classData.byteCode.length);
        buffer.put(classData.byteCode);
        try (CTypeConversion.CCharPointerHolder className = CTypeConversion.toCString(classData.binaryName)) {
            JNI.JClass definedClass = JNIUtil.DefineClass(
                            env,
                            className.get(),
                            classLoader,
                            classDataPointer,
                            classData.byteCode.length);
            return definedClass;
        } finally {
            UnmanagedMemory.free(classDataPointer);
            // LinkageError is allowed, the class may be already defined
            checkException(env, "Failed to define " + classData.binaryName, LinkageError.class);
        }
    }

    private static void registerNatives(JNI.JNIEnv env, JNI.JObject classLoader, JNI.JClass target) {
        JNI.JClass libgraalClass = findClassInHotSpot(env, classLoader, JMXFromLibGraalCalls.CLASS_LIBGRAAL, true);
        JMXFromLibGraalCalls.registerNatives(env, libgraalClass, target);
        checkException(env, "Failed to register natives");
    }

    /**
     * Gets a reference to factory thread running in HotSpot heap.
     */
    private static JNI.JObject getFactory(JNI.JNIEnv env, JNI.JClass entryPoints) {
        JNI.JObject factory = JMXFromLibGraalCalls.getFactory(env, entryPoints);
        checkException(env, "Failed to instantiate MBean factory on HotSpot side");
        assert factory.isNonNull() : "Factory cannot be null.";
        return factory;
    }

    /**
     * Notifies the factory thread in HotSpot heap of new management bean instances to register.
     */
    @SuppressWarnings("try")
    private static void signalRegistrationRequest() {
        JNI.JNIEnv env = getCurrentJNIEnv();
        JNI.JClass entryPoints = getHotSpotEntryPoints();
        JNI.JObject factory = getFactory(env, entryPoints);
        JMXFromLibGraalCalls.signalRegistrationRequest(env, entryPoints, factory);
        checkException(env, "Failed to register MBeans");
    }

    /**
     * Performs MBeans unregistration in the HotSpot heap.
     */
    private static void unregister(List<MBeanProxy<?>> toUnregister) {
        JNI.JNIEnv env = getCurrentJNIEnv();
        JNI.JClass entryPoints = getHotSpotEntryPoints();
        JNI.JObject factory = getFactory(env, entryPoints);
        JNI.JObjectArray objectNamesHandle = JNIUtil.NewObjectArray(env, toUnregister.size(), JMXFromLibGraalCalls.findClass(env, getBinaryName(String.class.getName())), WordFactory.nullPointer());
        for (int i = 0; i < toUnregister.size(); i++) {
            JNI.JString objectName = JNIUtil.createHSString(env, toUnregister.get(i).getName());
            JNIUtil.SetObjectArrayElement(env, objectNamesHandle, i, objectName);
        }
        JMXFromLibGraalCalls.unregister(env, entryPoints, factory, objectNamesHandle);
        checkException(env, "Failed to unregister MBeans");
    }

    /**
     * Unblocks the threads in other isolates waiting for native methods registration.
     */
    private static void notifyNativesRegistered(JNI.JNIEnv env, JNI.JClass toLibGraalCalls) {
        JMXFromLibGraalCalls.notifyNativesRegistered(env, toLibGraalCalls);
        checkException(env, "Failed to release register natives spin lock.");
    }

    /**
     * Blocks waiting for the other isolate thread to register native methods in the
     * {@code HotSpotToSVMEntryPoints} class.
     */
    private static void waitForRegisterNatives(JNI.JNIEnv env, JNI.JClass toLibGraalCalls) {
        JMXFromLibGraalCalls.waitForRegisterNatives(env, toLibGraalCalls);
        checkException(env, "Failed to release register natives spin lock.");
    }

    /**
     * Lifecycle state.
     */
    private enum State {
        /**
         * Initial state when an isolate is created.
         */
        ACTIVE,

        /**
         * Closed, new MBean registrations are no longer accepted.
         */
        CLOSED
    }

    private static final class OnShutDown implements Runnable {

        @Override
        public void run() {
            List<MBeanProxy<?>> toUnregister;
            synchronized (MBeanProxy.class) {
                state = MBeanProxy.State.CLOSED;
                toUnregister = mBeansToUnregisterOnIsolateClose;
                mBeansToUnregisterOnIsolateClose = null;
            }
            unregister(toUnregister);
        }
    }

    /**
     * Represents a class defined in the HotSpot heap. The {@code ClassData} objects are created
     * when building libgraal.
     *
     */
    private static final class ClassData {
        final String binaryName;
        final byte[] byteCode;

        private ClassData(String binaryName, byte[] byteCode) {
            this.binaryName = binaryName;
            this.byteCode = byteCode;
        }

        static ClassData create(Class<?> clz) {
            String binaryName = getBinaryName(clz.getName());
            try (DataInputStream in = new DataInputStream(clz.getResourceAsStream('/' + binaryName + ".class"))) {
                byte[] buffer = new byte[in.available()];
                in.readFully(buffer);
                return new ClassData(binaryName, buffer);
            } catch (IOException ioe) {
                throw new InternalError("Error loading class file for %s: " + clz.getName(), ioe);
            }
        }
    }
}
