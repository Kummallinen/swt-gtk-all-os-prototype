## Installing GTK on Mac
Install GTK with Macports. Make sure to only install the "quartz" versions of packages. 
"sudo port install gtk4 -quartz"

##Building and compiling gtk4
Install Python (3.7.17 is confirmed to work)
Install Meson with macports
"sudo port install meson"
Follow instructions at https://docs.gtk.org/gtk4/building.html to build and compile GTK4. 


##Build GTK4 bindings for Eclipse 
In order to use GTK in eclipse you must run ./build.sh, found in /bundles/org.eclipse.swt/Eclipse SWT PI/gtk/library/build.sh.
Requires:
* cairo
* ATK lib
* Webkit lib
* GLX lib
Make sure you are installing quartz versions with Macports where applicable.
Then run "./build.sh -gtk4"


