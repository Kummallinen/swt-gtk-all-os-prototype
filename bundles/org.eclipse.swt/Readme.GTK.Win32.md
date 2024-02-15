Original issue tracker for Windows GTK port: https://bugs.eclipse.org/bugs/show_bug.cgi?id=488431

1. Install msys2: https://msys2.github.io/
2. Update msys2:
     ``` 
     pacman -Syuu
     ``` 
3. Install toolchain and dependencies:
      ```
      pacman -S mingw-w64-x86_64-toolchain
      pacman -S mingw-w64-x86_64-gtk2
      pacman -S mingw-w64-x86_64-webkitgtk2
      pacman -S mingw-w64-x86_64-gtk3
      pacman -S mingw-w64-x86_64-webkitgtk3
      ```
4. Open an ordinary Windows console and build the GTK binaries on Windows. Run the following in the fragment project:
      ``` 
      mvn clean process-resources -Dnative=gtk.win32.x86_64 -Dws=gtk -DskipTests
      ```

