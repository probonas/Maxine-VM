/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.compiler.ir.igv;

import com.sun.max.vm.compiler.bir.*;
import com.sun.max.vm.compiler.cir.*;
import com.sun.max.vm.compiler.dir.*;
import com.sun.max.vm.compiler.eir.*;
import com.sun.max.vm.compiler.ir.*;

/**
 * Initializes the properties of a group of graphs for a specific method.
 *
 * @author Thomas Wuerthinger
 */
class InitializeGroupIrMethodVisitor implements IrMethodVisitor {

    private static final String TYPE_PROPERTY_NAME = "type";
    private static final String NAME_PROPERTY_NAME = "type";
    private static final String BIR_TYPE_PROPERTY_VALUE = "com.sun.max.vm.compiler.bir";
    private static final String BIR_NAME_PROPERTY_SUFFIX = "BIR";
    private static final String CIR_TYPE_PROPERTY_VALUE = "com.sun.max.vm.compiler.cir";
    private static final String CIR_NAME_PROPERTY_SUFFIX = "CIR";
    private static final String DIR_TYPE_PROPERTY_VALUE = "com.sun.max.vm.compiler.dir";
    private static final String DIR_NAME_PROPERTY_SUFFIX = "DIR";
    private static final String EIR_TYPE_PROPERTY_VALUE = "com.sun.max.vm.compiler.eir";
    private static final String EIR_NAME_PROPERTY_SUFFIX = "EIR";

    private final GraphWriter.Group _group;

    InitializeGroupIrMethodVisitor(GraphWriter.Group group) {
        _group = group;
    }

    private void initGroupName(GraphWriter.Group group, IrMethod method, String suffix) {
        group.getProperties().setProperty(NAME_PROPERTY_NAME, method.classMethodActor().format("%H.%n(%p)") + " / " + suffix);
    }

    @Override
    public void visit(BirMethod method) {
        _group.getProperties().setProperty(TYPE_PROPERTY_NAME, BIR_TYPE_PROPERTY_VALUE);
        initGroupName(_group, method, BIR_NAME_PROPERTY_SUFFIX);
    }

    @Override
    public void visit(CirMethod method) {
        _group.getProperties().setProperty(TYPE_PROPERTY_NAME, CIR_TYPE_PROPERTY_VALUE);
        initGroupName(_group, method, CIR_NAME_PROPERTY_SUFFIX);
    }

    @Override
    public void visit(DirMethod method) {
        _group.getProperties().setProperty(TYPE_PROPERTY_NAME, DIR_TYPE_PROPERTY_VALUE);
        initGroupName(_group, method, DIR_NAME_PROPERTY_SUFFIX);

    }

    @Override
    public void visit(EirMethod method) {
        _group.getProperties().setProperty(TYPE_PROPERTY_NAME, EIR_TYPE_PROPERTY_VALUE);
        initGroupName(_group, method, EIR_NAME_PROPERTY_SUFFIX);
    }
}
