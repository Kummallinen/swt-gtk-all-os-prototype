#*******************************************************************************
# Copyright (c) 2000, 2003 IBM Corporation and others.
# All rights reserved. This program and the accompanying materials 
# are made available under the terms of the Common Public License v1.0
# which accompanies this distribution, and is available at
# http://www.eclipse.org/legal/cpl-v10.html
# 
# Contributors:
#     IBM Corporation - initial API and implementation
#     Kevin Cornell (Rational Software Corporation)
#     Tom Tromey (Red Hat, Inc.)
#*******************************************************************************

#!/bin/sh

# Determine the operating system being built
OS=`uname -s`

# build according to the operating system
case $OS in

    "AIX")
        if  [ "$1" = "clean" ]; then
            make -f make_aix.mak clean
        else
            echo "Building AIX version of SWT and CDE DLLs."
            make -f make_aix.mak make_swt
            make -f make_aix.mak make_cde
        fi
        ;;

    "Linux")
        if  [ "$1" = "clean" ]; then
            make -f make_linux.mak clean
        else
            echo "Building Linux version of SWT and GNOME DLLs."
            make -f make_linux.mak make_swt make_awt make_gnome make_gtk

            build_kde=`rpm -q kdebase | grep "not installed"`
            if [ "$build_kde" = "" ]; then
                echo "Building Linux version of KDE DLL."
                make -f make_linux.mak make_kde
            fi
        fi
        ;;

    "SunOS")
        if  [ "$1" = "clean" ]; then
            make -f make_solaris.mak clean
        else
            echo "Building Solaris version of SWT and CDE DLLs."
            make -f make_solaris.mak make_swt
            make -f make_solaris.mak make_cde
        fi
        ;;

        "HP-UX")
	if  [ "$1" = "clean" ]; then
            make -f make_hpux.mak clean
        else
            echo "Building HP-UX version of SWT and CDE DLLs."
            make -f make_hpux.mak make_swt
            make -f make_hpux.mak make_cde
        fi
        ;;

    *)
        echo "Unknown OS -- build aborted"
        ;;
esac
