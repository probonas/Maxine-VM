/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.vm.code;

import com.sun.max.annotate.INSPECTED;
import com.sun.max.annotate.NEVER_INLINE;
import com.sun.max.unsafe.Address;
import com.sun.max.unsafe.Pointer;
import com.sun.max.unsafe.Size;
import com.sun.max.vm.Log;
import com.sun.max.vm.MaxineVM;
import com.sun.max.vm.VMOptions;
import com.sun.max.vm.VMSizeOption;
import com.sun.max.vm.actor.holder.ClassActor;
import com.sun.max.vm.actor.member.MethodActor;
import com.sun.max.vm.compiler.target.TargetBundleLayout;
import com.sun.max.vm.compiler.target.TargetBundleLayout.ArrayField;
import com.sun.max.vm.compiler.target.TargetMethod;
import com.sun.max.vm.heap.Cell;
import com.sun.max.vm.heap.CellVisitor;
import com.sun.max.vm.heap.Heap;
import com.sun.max.vm.heap.debug.DebugHeap;
import com.sun.max.vm.layout.Layout;
import com.sun.max.vm.reference.Reference;
import com.sun.max.vm.runtime.SafepointPoll;
import com.sun.max.vm.tele.InspectableCodeInfo;
import com.sun.max.vm.type.ClassRegistry;

import java.util.Arrays;

import static com.sun.max.vm.MaxineVM.isHosted;
import static com.sun.max.vm.VMOptions.register;

/**
 * Target machine code cache management.
 *
 * All generated code is position independent as a whole, but target methods may contain direct call references between
 * each other and these must be within 32-bit offsets! Therefore all code regions must be within 32-bit offsets from
 * each other. A concrete implementation of this class must enforce this invariant.
 */
public abstract class CodeManager {

    /**
     * VM option for specifying the amount of memory to be reserved for the runtime baseline code region cache.
     * Experiments have shown that a vast amount of baseline code is generated, so this region is rather large until
     * code eviction logic is properly in place.
     */
    public static final VMSizeOption runtimeBaselineCodeRegionSize =
        register(new VMSizeOption("-XX:ReservedBaselineCodeCacheSize=", Size.M.times(128),
            "Memory allocated for runtime code region cache."), MaxineVM.Phase.PRISTINE);

    /**
     * VM option for specifying the amount of memory to be reserved for the runtime opt code region cache.
     * Experiments have shown that very little such code is generated, so this region is rather small.
     */
    public static final VMSizeOption runtimeOptCodeRegionSize =
        register(new VMSizeOption("-XX:ReservedOptCodeCacheSize=", Size.M.times(16),
            "Memory allocated for runtime code region cache."), MaxineVM.Phase.PRISTINE);

    private int nAllocations = 0;

    private int lastSurvivorSize;
    private int largestSurvivorSize = 0;

    protected void recordSurvivorSize(int survivorSize) {
        lastSurvivorSize = survivorSize;
        if (survivorSize > largestSurvivorSize) {
            largestSurvivorSize = survivorSize;
        }
    }

    /**
     * Performs code cache validation.
     * This has a return type of {@code boolean} so that it can be used as the condition in an
     * assertion. That is, code cache validation is predicated on assertions being enabled.
     */
    private boolean validateCodeCache() {
        CodeCacheValidation.instance.submit();
        return true;
    }

    /**
     * This option can be used to force baseline code cache contention every N method allocations.
     * It monitors the amount of surviving methods/bytes after each contention and dumps those, including the largest.
     */
    public static int CodeCacheContentionFrequency;

    static {
        VMOptions.addFieldOption("-XX:", "CodeCacheContentionFrequency", CodeManager.class,
            "Enforce baseline code cache contention every N method allocations.", MaxineVM.Phase.STARTING);
    }

    /**
     * Categorization of how long a method is destined to stay around.
     */
    public enum Lifespan {
        /**
         * Class initializers etc.
         */
        ONE_SHOT,
        /**
         * Methods that will likely be removed after some time (e.g., compiled by a baseline compiler).
         */
        SHORT,
        /**
         * Methods that stay (e.g., compiled by an optimizing compiler).
         */
        LONG;
    }

    /**
     * The baseline code region contains machine code generated by the baseline compiler.
     */
    @INSPECTED
    protected static final SemiSpaceCodeRegion runtimeBaselineCodeRegion = new SemiSpaceCodeRegion("Code-Runtime-Baseline");

    /**
     * The opt code region contains machine code generated by the optimising compiler as well as adapters and trampolines.
     */
    @INSPECTED
    protected static final CodeRegion runtimeOptCodeRegion = new CodeRegion("Code-Runtime-Opt");

    /**
     * Get the runtime baseline code region.
     * @return the runtime baseline code region
     */
    public SemiSpaceCodeRegion getRuntimeBaselineCodeRegion() {
        return runtimeBaselineCodeRegion;
    }

    /**
     * Get the runtime opt code region.
     * @return the runtime baseline code region
     */
    public CodeRegion getRuntimeOptCodeRegion() {
        return runtimeOptCodeRegion;
    }

    /**
     * Initialize this code manager.
     */
    void initialize() {
    }

    private static int BOOT_TO_BASELINE_INITIAL_SIZE = 10;

    /**
     * Records all direct call links from the boot code region to the baseline code region.
     */
    private static TargetMethod[] bootToBaseline = new TargetMethod[BOOT_TO_BASELINE_INITIAL_SIZE];

    private static int nBootToBaseline = 0;

    public static int bootToBaselineSize() {
        return nBootToBaseline;
    }

    public static synchronized void recordBootToBaselineCaller(final TargetMethod tm) {
        if (CodeEviction.logging()) {
            CodeEviction.codeEvictionLogger.logBootToBaseline(tm);
        }
        if (nBootToBaseline == bootToBaseline.length) {
            bootToBaseline = Arrays.copyOf(bootToBaseline, bootToBaseline.length * 2);
        }
        bootToBaseline[nBootToBaseline] = tm;
        ++nBootToBaseline;
    }

    public static TargetMethod[] bootToBaselineCallers() {
        return bootToBaseline;
    }

    public static void bootToBaselineDo(final TargetMethod.Closure closure) {
        for (int i = 0; i < nBootToBaseline; i++) {
            if (!closure.doTargetMethod(bootToBaseline[i])) {
                return;
            }
        }
    }

    /**
     * Allocates memory for the code-related arrays of a given target method
     * and {@linkplain TargetMethod#setCodeArrays(byte[], Pointer, byte[], Object[]) initializes} them.
     *
     * @param targetBundleLayout describes the layout of the arrays in the allocated space
     * @param targetMethod the target method for which the code-related arrays are allocated
     * @param inHeap specifies if the memory should be allocated in a code region or on the heap
     */
    synchronized void allocate(TargetBundleLayout targetBundleLayout, TargetMethod targetMethod, boolean inHeap, Lifespan lifespan) {
        final Size bundleSize = targetBundleLayout.bundleSize();
        int codeLength = targetBundleLayout.length(ArrayField.code);
        int scalarLiteralsLength = targetBundleLayout.length(ArrayField.scalarLiterals);
        int referenceLiteralsLength = targetBundleLayout.length(ArrayField.referenceLiterals);
        final Size allocationSize;
        CodeRegion currentCodeRegion = null;

        allocationSize = bundleSize;
        Object allocationTraceDescription = Code.TraceCodeAllocation ? (targetMethod.classMethodActor() == null ? targetMethod.regionName() : targetMethod.classMethodActor()) : null;

        Pointer start;
        boolean mustReenableSafepoints = false;
        if (inHeap) {
            assert !isHosted();
            int byteArraySize = allocationSize.minus(Layout.byteArrayLayout().headerSize()).toInt();
            byte[] buf = new byte[byteArraySize];

            // 'buf' must not move until it has been reformatted
            mustReenableSafepoints = !SafepointPoll.disable();

            start = Layout.originToCell(Reference.fromJava(buf).toOrigin());
        } else {
            if (!isHosted()) {
                // The allocation and initialization of objects in a code region must be atomic with respect to garbage collection.
                mustReenableSafepoints = !SafepointPoll.disable();
                Heap.disableAllocationForCurrentThread();
                if (lifespan == Lifespan.LONG) {
                    currentCodeRegion = runtimeOptCodeRegion;
                } else {
                    currentCodeRegion = runtimeBaselineCodeRegion;
                }
            } else {
                currentCodeRegion = Code.bootCodeRegion();
            }

            if (currentCodeRegion == runtimeBaselineCodeRegion && CodeCacheContentionFrequency > 0 && ++nAllocations % CodeCacheContentionFrequency == 0) {
                start = Pointer.zero();
            } else {
                start = currentCodeRegion.allocate(allocationSize, false);
	        //Log.print("START set to ");Log.println(start);

            }

            // Allocation in the baseline code region may take another attempt upon contention, after compaction.
            if (start.isZero() && currentCodeRegion == runtimeBaselineCodeRegion) {
                CodeEviction.run();
                assert validateCodeCache();
                start = currentCodeRegion.allocate(allocationSize, false);
                if (CodeCacheContentionFrequency > 0 && CodeEviction.logging()) {
                    CodeEviction.codeEvictionLogger.logStats_Surviving(lastSurvivorSize, largestSurvivorSize);
                }
            }
        }

        traceChunkAllocation(allocationTraceDescription, allocationSize, start, inHeap);
        if (start.isZero()) {
            if (mustReenableSafepoints) {
                SafepointPoll.enable();
            }
            Heap.enableAllocationForCurrentThread();
            Log.print("Out of memory allocating in code region named " + currentCodeRegion.regionName());
            if (currentCodeRegion == runtimeBaselineCodeRegion) {
                Log.println(" - try larger value for " + runtimeBaselineCodeRegionSize.toString() + "<n>");
            } else if (currentCodeRegion == runtimeOptCodeRegion) {
                Log.println(" - try larger value for " + runtimeOptCodeRegionSize.toString() + "<n>");
            }
            MaxineVM.exit(11);
        }

        targetMethod.setStart(start);
        targetMethod.setSize(allocationSize);
        //Log.print("START set again to ");Log.println(start);




        // Initialize the objects in the allocated space so that they appear as a set of contiguous
        // well-formed objects that can be traversed.
        byte[] code;
        byte[] scalarLiterals = null;
        Object[] referenceLiterals = null;
        if (MaxineVM.isHosted()) {
            code = new byte[codeLength];
            scalarLiterals = scalarLiteralsLength == 0 ? null : new byte[scalarLiteralsLength];
            referenceLiterals = referenceLiteralsLength == 0 ? null : new Object[referenceLiteralsLength];
        } else {
            final Pointer codeCell = targetBundleLayout.cell(start, ArrayField.code);
            code = (byte[]) Cell.plantArray(codeCell, ClassRegistry.BYTE_ARRAY.dynamicHub(), codeLength);
            if (scalarLiteralsLength != 0) {
                final Pointer scalarLiteralsCell = targetBundleLayout.cell(start, ArrayField.scalarLiterals);
                scalarLiterals = (byte[]) Cell.plantArray(scalarLiteralsCell, ClassRegistry.BYTE_ARRAY.dynamicHub(), scalarLiteralsLength);
            }
            if (referenceLiteralsLength != 0) {
                final Pointer referenceLiteralsCell = targetBundleLayout.cell(start, ArrayField.referenceLiterals);
                referenceLiterals = (Object[]) Cell.plantArray(referenceLiteralsCell, ClassActor.fromJava(Object[].class).dynamicHub(), referenceLiteralsLength);
            }
            if (Code.TraceCodeAllocation) {
                traceAllocation(targetBundleLayout, bundleSize, scalarLiteralsLength, referenceLiteralsLength, start, codeCell);
            }
        }

        final Pointer codeStart = targetBundleLayout.firstElementPointer(start, ArrayField.code);
        targetMethod.setCodeArrays(code, codeStart, scalarLiterals, referenceLiterals);
        if (currentCodeRegion == runtimeBaselineCodeRegion) {
            targetMethod.protect();
        }
	//Log.print("Pointer codeStart set to ");Log.println(codeStart);

        if (!MaxineVM.isHosted()) {
            // It is now safe again to perform operations that may block and/or trigger a garbage collection
            if (mustReenableSafepoints) {
                SafepointPoll.enable();
            }
            if (!inHeap) {
	                //Log.println("NOTINHEAP");

                Heap.enableAllocationForCurrentThread();
            }
        }

        if (currentCodeRegion != null) {
	        //Log.println("ADDEDTOCURRCODEREGI");

            	currentCodeRegion.add(targetMethod);
		//Log.println(targetMethod.toString());
		//Log.println(targetMethod.start());
        }
    }

    private void traceAllocation(TargetBundleLayout targetBundleLayout, Size bundleSize, int scalarLiteralsLength, int referenceLiteralsLength, Pointer start, Pointer codeCell) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Code arrays: code=[");
        Log.print(codeCell);
        Log.print(" - ");
        Log.print(targetBundleLayout.cellEnd(start, ArrayField.code));
        Log.print("], scalarLiterals=");
        if (scalarLiteralsLength > 0) {
            Log.print(targetBundleLayout.cell(start, ArrayField.scalarLiterals));
            Log.print(" - ");
            Log.print(targetBundleLayout.cellEnd(start, ArrayField.scalarLiterals));
            Log.print("], referenceLiterals=");
        } else {
            Log.print("0, referenceLiterals=");
        }
        if (referenceLiteralsLength > 0) {
            Log.print(targetBundleLayout.cell(start, ArrayField.referenceLiterals));
            Log.print(" - ");
            Log.print(targetBundleLayout.cellEnd(start, ArrayField.referenceLiterals));
            Log.println("]");
        } else {
            Log.println(0);
        }
        Log.unlock(lockDisabledSafepoints);
    }

    private void traceChunkAllocation(Object purpose, Size size, Pointer cell, boolean inHeap) {
        if (!cell.isZero() && purpose != null) {
            final boolean lockDisabledSafepoints = Log.lock();
            Log.printCurrentThread(false);
            if (inHeap) {
                Log.print(": Allocated chunk in heap for ");
            } else {
                Log.print(": Allocated chunk in code cache for ");
            }
            if (purpose instanceof MethodActor) {
                Log.printMethod((MethodActor) purpose, false);
            } else {
                Log.print(purpose);
            }
            Log.print(" at ");
            Log.print(cell);
            Log.print(" [size ");
            Log.print(size.wordAligned().toInt());
            Log.print(", end=");
            Log.print(cell.plus(size.wordAligned()));
            Log.println(']');
            Log.unlock(lockDisabledSafepoints);
        }
    }

    /**
     * Looks up the code region in which the specified code pointer lies. This lookup includes
     * the boot code region.
     *
     * @param codePointer the code pointer
     * @return a reference to the code region that contains the specified code pointer, if one exists; {@code null} if
     *         the code pointer lies outside of all code regions
     */
    CodeRegion codePointerToCodeRegion(Address codePointer) {
        if (Code.bootCodeRegion().contains(codePointer)) {
	    //Log.println("IN boot");

            return Code.bootCodeRegion();
        }
        if (runtimeBaselineCodeRegion.contains(codePointer)) {
	    //Log.println("IN runtimebaseline");

            return runtimeBaselineCodeRegion;
        }
        if (runtimeOptCodeRegion.contains(codePointer)) {
            //Log.println("IN runtime");

            return runtimeOptCodeRegion;
        }
        if (VMOptions.verboseOption.verboseCompilation) {
            Log.print("NOT found in any codeRegions ");
            Log.println(codePointer);
        }

        return null;
    }

    /**
     * Looks up the target method that contains the specified code pointer.
     *
     * @param codePointer the code pointer to lookup
     * @return the target method that contains the specified code pointer, if it exists; {@code null}
     * if no target method contains the specified code pointer
     */
    TargetMethod codePointerToTargetMethod(Address codePointer) {
        TargetMethod result = null;
        final CodeRegion codeRegion = codePointerToCodeRegion(codePointer);
        if (codeRegion != null) {
            result = codeRegion.find(codePointer);
        }
        return result;
    }

    /**
     * Visit the cells in all the code regions in this code manager.
     *
     * @param cellVisitor the visitor to call back for each cell in each region
     * @param includeBootCode specifies if the cells in the {@linkplain Code#bootCodeRegion() boot code region} should
     *            also be visited
     */
    void visitCells(CellVisitor cellVisitor, boolean includeBootCode) {
        if (includeBootCode) {
            visitAllIn(cellVisitor, Code.bootCodeRegion());
        }
        visitAllIn(cellVisitor, runtimeBaselineCodeRegion);
        visitAllIn(cellVisitor, runtimeOptCodeRegion);
    }

    void visitAllIn(CellVisitor v, CodeRegion cr) {
        Pointer firstCell = cr.gcstart().asPointer();
        Pointer cell = firstCell;
        while (cell.lessThan(cr.getAllocationMark())) {
            cell = DebugHeap.checkDebugCellTag(firstCell, cell);
            cell = v.visitCell(cell);
        }
    }

    /**
     * Return size of runtime baseline code region.
     * @return size of runtime baseline code region
     */
    public Size getRuntimeBaselineCodeRegionSize() {
        return runtimeBaselineCodeRegionSize.getValue();
    }

    /**
     * Return size of runtime opt code region.
     * @return size of runtime opt code region
     */
    public Size getRuntimeOptCodeRegionSize() {
        return runtimeOptCodeRegionSize.getValue();
    }

    /**
     * By definition, short-lived methods go to the baseline code region.
     */
    public static boolean isShortlived(TargetMethod tm) {
        return runtimeBaselineCodeRegion.contains(tm.start());
    }

    /**
     * A collection of methods that support certain inspection services.
     * The public methods are to be called by all implementations when
     * the specified events occur.
     *
     */
    public static final class Inspect {

        /**
         * Announces that a code eviction is about to begin.  It does almost
         * nothing, but it must be called by managed code region implementations for
         * certain Inspector services to work.
         * <p>
         * This should be called after any preliminary steps that do not modify
         * the code region have been completed.
         *
         * @param codeRegion the region of VM code cache in which eviction is about to start
         */
        public static void notifyEvictionStarted(CodeRegion codeRegion) {
            InspectableCodeInfo.notifyEvictionStarted(codeRegion);
            inspectableCodeEvictionStarted();
        }

        /**
         * Announces that a code eviction has finished.  It does almost
         * nothing, but it must be called by managed code region implementations for
         * certain Inspector services to work.
         * <p>
         * This should be called as soon as possible after all changes have been
         * made, for example before any non-destructive verification..
         *
         * @param codeRegion the region of VM code cache in which eviction just completed
         */
        public static void notifyEvictionCompleted(CodeRegion codeRegion) {
            InspectableCodeInfo.notifyEvictionCompleted(codeRegion);
            inspectableCodeEvictionCompleted();
        }

        private Inspect() {
        }

        /**
         * An empty method whose purpose is to be interrupted by the Inspector
         * at the beginning of a code eviction.
         * <p>
         * This particular method is intended for  use by users of the Inspector, and
         * is distinct from a method used by the Inspector for internal use.
         * <p>
         * <strong>Important:</strong> The Inspector assumes that this method is loaded
         * and compiled in the boot image and that it will never be dynamically recompiled.
         */
        @INSPECTED
        @NEVER_INLINE
        private static void inspectableCodeEvictionStarted() {
        }

        /**
         * An empty method whose purpose is to be interrupted by the Inspector
         * at the completion of a code eviction.
         * <p>
         * This particular method is intended for  use by users of the Inspector, and
         * is distinct from a method used by the Inspector for internal use.
         * <p>
         * <strong>Important:</strong> The Inspector assumes that this method is loaded
         * and compiled in the boot image and that it will never be dynamically recompiled.
         */
        @INSPECTED
        @NEVER_INLINE
        private static void inspectableCodeEvictionCompleted() {
        }
    }

}
