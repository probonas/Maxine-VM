/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2015, Andrey Rodchenko. All rights reserved.
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */
package com.oracle.max.vm.ext.maxri;

import static com.sun.max.vm.MaxineVM.*;

import java.lang.annotation.*;
import java.lang.reflect.*;

import com.sun.cri.ci.*;
import com.sun.max.annotate.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.ClassActor;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.SymbolTable;
import com.sun.max.vm.classfile.constant.Utf8Constant;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.object.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;

/**
 * This class contains the implementation of runtime calls that are called by code emitted by the CRI compilers.
 */
public class MaxRuntimeCalls {

    // a local flag to enable calls to verify the reference map for each runtime call
    private static final boolean ENABLE_REFMAP_VERIFICATION = true;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface MAX_RUNTIME_ENTRYPOINT {

        CiRuntimeCall runtimeCall() default CiRuntimeCall.GenericCallback;
    }

    public static ClassMethodActor getClassMethodActor(CiRuntimeCall call) {
        final ClassMethodActor result = runtimeCallMethods[call.ordinal()];
        assert result != null : "no runtime method defined for " + call;
        return result;
    }

    private static ClassMethodActor[] runtimeCallMethods = new ClassMethodActor[CiRuntimeCall.values().length];

    static {
        for (Method method : MaxRuntimeCalls.class.getMethods()) {
            MAX_RUNTIME_ENTRYPOINT entry = method.getAnnotation(MAX_RUNTIME_ENTRYPOINT.class);
            if (entry != null) {
                registerMethod(method, entry.runtimeCall());
            }
        }

        for (CiRuntimeCall call : CiRuntimeCall.values()) {
            assert checkCompatible(call, getClassMethodActor(call));
        }

        MaxTargetMethod.initializeMaxRuntimeCallsRuntimeUnwindExceptionMethodActor(getRuntimeUnwindExceptionMethodActor());
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.UnwindException)
    public static void runtimeUnwindException(Throwable throwable) throws Throwable {
        Throw.raise(throwable);
    }

    /**
     * Returns method actor for {@link #runtimeUnwindException} method of {@link MaxRuntimeCalls) class.
     */
    @HOSTED_ONLY
    private static StaticMethodActor getRuntimeUnwindExceptionMethodActor() {
        Utf8Constant runtimeUnwindExceptionMethodName = SymbolTable.makeSymbol("runtimeUnwindException");
        return ClassActor.fromJava(MaxRuntimeCalls.class).findLocalStaticMethodActor(runtimeUnwindExceptionMethodName);
    }

    @HOSTED_ONLY
    private static boolean checkCompatible(CiRuntimeCall call, ClassMethodActor classMethodActor) {
        assert classMethodActor.descriptor().returnKind(true) == call.resultKind;
        CiKind[] kinds = CiUtil.signatureToKinds(classMethodActor);
        for (int i = 0; i < call.arguments.length; i++) {
            assert kinds[i] == call.arguments[i] : call + " incompatible with " + classMethodActor;
        }
        return true;
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.d2long)
    public static long runtimeJd2jlong(double val) {
        verifyRefMaps();
        return Snippets.d2long(val);

    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.f2long)
    public static long runtimeJf2jlong(float val) {
        verifyRefMaps();
        return Snippets.f2long(val);

    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.l2double)
    public static double runtimel2double(long val) {
        verifyRefMaps();
        return Snippets.l2double(val);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.l2float)
    public static float runtimel2float(long val) {
        verifyRefMaps();
        return Snippets.l2float(val);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.arithmeticldiv)
    public static long runtimearithmeticldiv(long a, long b) {
        verifyRefMaps();
        return MaxineVM.arithmeticldiv(a, b);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.arithmeticludiv)
    public static long runtimearithmeticludiv(long a, long b) {
        verifyRefMaps();
        return MaxineVM.arithmeticludiv(a, b);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.arithmeticlrem)
    public static long runtimearithmeticlrem(long a, long b) {
        verifyRefMaps();
        return MaxineVM.arithmeticlrem(a, b);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.arithmeticlurem)
    public static long runtimearithmeticlurem(long a, long b) {
        verifyRefMaps();
        return MaxineVM.arithmeticlurem(a, b);
    }

    private static void verifyRefMaps() {
        if (ENABLE_REFMAP_VERIFICATION && StackReferenceMapPreparer.VerifyRefMaps) {
            StackReferenceMapPreparer.verifyReferenceMapsForThisThread();
        }
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.RegisterFinalizer)
    public static void runtimeRegisterFinalizer(Object object) {
        verifyRefMaps();
        if (ObjectAccess.readClassActor(object).hasFinalizer()) {
            SpecialReferenceManager.registerFinalizee(object);
        }
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.HandleException)
    public static void runtimeHandleException(Throwable throwable) throws Throwable {
        verifyRefMaps();
        Throw.raise(throwable);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.OSRMigrationEnd)
    public static void runtimeOSRMigrationEnd() {
        verifyRefMaps();
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.JavaTimeMillis)
    public static long runtimeJavaTimeMillis() {
        verifyRefMaps();
        return MaxineVM.native_currentTimeMillis();
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.JavaTimeNanos)
    public static long runtimeJavaTimeNanos() {
        verifyRefMaps();
        return MaxineVM.native_nanoTime();
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.Debug)
    public static void runtimeDebug() {
        verifyRefMaps();
        throw FatalError.unexpected("Debug");
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmethicLrem)
    public static long runtimeArithmethicLrem(long a, long b) {
        verifyRefMaps();
        throw FatalError.unexpected("Compiler should directly translate LREM");
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticLdiv)
    public static long runtimeArithmeticLdiv(long a, long b) {
        verifyRefMaps();
        throw FatalError.unexpected("Compiler should directly translate LDIV");
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticFrem)
    public static float runtimeArithmeticFrem(float v1, float v2) {
        verifyRefMaps();
        return Snippets.floatRemainder(v1, v2);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticDrem)
    public static double runtimeArithmeticDrem(double v1, double v2) {
        verifyRefMaps();
        return Snippets.doubleRemainder(v1, v2);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticCos)
    public static double runtimeArithmeticCos(double v) {
        verifyRefMaps();
        return Math.cos(v);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticTan)
    public static double runtimeArithmeticTan(double v) {
        verifyRefMaps();
        return Math.tan(v);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticLog)
    public static double runtimeArithmeticLog(double v) {
        verifyRefMaps();
        return Math.log(v);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticLog10)
    public static double runtimeArithmeticLog10(double v) {
        verifyRefMaps();
        return Math.log10(v);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.ArithmeticSin)
    public static double runtimeArithmeticSin(double v) {
        verifyRefMaps();
        return Math.sin(v);
    }

    /**
     * The body of this method is provided by {@link Stubs#genUncommonTrapStub()}.
     */
    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.Deoptimize)
    public static void uncommonTrap() {
        throw FatalError.unexpected("stub should be overwritten");
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.SetDeoptInfo)
    public static void setDeoptInfo(Object info) {
        // TODO
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.CreateNullPointerException)
    public static Object createNullPointerException() {
        return new NullPointerException();
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.CreateOutOfBoundsException)
    public static Object createOutOfBoundsException(int index) {
        return new ArrayIndexOutOfBoundsException(index);
    }

    @MAX_RUNTIME_ENTRYPOINT(runtimeCall = CiRuntimeCall.GenericCallback)
    public static Object genericCallback(CiGenericCallback cb, Object arg) {
        throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxRuntimeCalls.genericCallback");
    }

    @HOSTED_ONLY
    private static void registerMethod(Method selectedMethod, CiRuntimeCall call) {
        ClassMethodActor classMethodActor = null;
        assert runtimeCallMethods[call.ordinal()] == null : "method already defined";
        classMethodActor = ClassMethodActor.fromJava(selectedMethod);
        runtimeCallMethods[call.ordinal()] = classMethodActor;
        if (MaxineVM.isHosted()) {
            new CriticalMethod(classMethodActor, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        } else {
            vm().compilationBroker.compile(classMethodActor, null);
        }
    }
}
