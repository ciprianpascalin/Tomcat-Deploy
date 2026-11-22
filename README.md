# Tomcat-Deploy

* TomcatDeploy - Tomcat Control Panel & Live Deploy (Java 17+, Swing)
*
* Execution: java TomcatDeploy.java            (or compiled: javac TomcatDeploy.java && java TomcatDeploy)
*            Windows, without console window: javaw TomcatDeploy.java   (if run with "java", the program
*            restarts itself using "javaw" and releases the console; to keep it, use: -Dtd.console=true)
* Requirements: JDK 17+ (not just JRE – Java compilation is performed within the program using javax.tools). 
*
* Features:
*  - Windows + Linux (+ macOS) support: handles catalina.bat / catalina.sh scripts, path separators, and Maven commands. 
*  - Java (for Tomcat and Maven) and HotswapAgent (.jar) are selected within the program (from a list of detected JDKs or via "Other JDK..."). These locations are saved. The program automatically generates `bin/setenv.bat` (Windows) or `bin/setenv.sh` (Linux/macOS), configuring `JAVA_HOME` and `CATALINA_OPTS=-javaagent:...=autoHotswap=true`. 
*    Any existing `setenv` file is backed up as `setenv.*.bak`. 
*  - "Configure Tomcat" button: In addition to setting up `setenv`, it configures `modificationTestInterval=0` for the JSP servlet (in `conf/web.xml`) and adds `<Resources cachingAllowed="false"/>` (in `conf/context.xml`)—ensuring that saved JSP changes appear upon the first page refresh. Other files remain untouched; originals are preserved as `.bak` files. 
*  - System Tray: When minimized, the window hides in the system tray (no external tools like RBTray required). The icon indicates the current status (green = Tomcat running, red = compilation error). If the window is hidden, errors are displayed as notifications. *    On Linux, the icon is implemented via D-Bus (StatusNotifierItem – used by GNOME with AppIndicator, Zorin, Ubuntu, KDE, Cinnamon, and XFCE), as the classic system tray—which Java expects—no longer exists on these desktop environments.
*  - Appearance: On Linux, it uses a custom, compact theme (dark or light, depending on the desktop theme), whereas Java’s default GTK look tends to make buttons and rows appear bloated. On Windows, it uses the system’s native appearance. This can be adjusted via the "Appearance" list.
*  - The "Start Deploy" action performs a complete cleanup of the old application within Tomcat (removing `webapps/<context>`, `<context>.war`, and the `work/` cache directory). This requires confirmation (which can be disabled via a checkbox). The default context is `ROOT`; using a different name allows you to host multiple projects within the same Tomcat instance.
*  - Changes are applied IN-PLACE: the file in Tomcat is not deleted and recreated; instead, only the modified portions are updated (and no write operation occurs if the file remains identical).
*  - Resources follow the `pom.xml` configuration, including resource folders, include/exclude rules, and `${...}` / `@...@` filtering.
*  - Instant response: The system utilizes file system events (`inotify` / `ReadDirectoryChangesW`) to process changes immediately, without delay. A web file reaches Tomcat in ~1 ms, while a `.java` file is typically recompiled in 10–20 ms (achieved by keeping a compilation context "warm" for each file, with an additional standby context prepared in the background).
*    Classes that remain unchanged are not overwritten, ensuring that HotswapAgent does not perform unnecessary reloads. *  - Resumption: if the project, Tomcat instance, and context are the same as those from the last successful deployment,
*    no cleanup is performed and Maven is not executed; only the changes that occurred in the meantime are synchronized. 
*  - "Open in browser" button; an empty context defaults to ROOT (webapps/ROOT). 
*  - If dependencies in `pom.xml` are modified, the `WEB-INF/lib` directory is updated automatically (via Maven). 
*  - Generates `META-INF` just as it would after an `mvn clean package` command: `MANIFEST.MF` + `META-INF/maven/<groupId>/<artifactId>/`
*    (containing `pom.xml` and `pom.properties`), derived from the `pom.xml` file. 
*  - Multi-threaded: includes a scanner (1 thread), parallel file copying (via a thread pool), Java compilation (dedicated thread),
*    and background execution of Tomcat (start/stop) and Maven. The user interface remains responsive at all times. 
*  - The project selection dialog opens on the Desktop, while the Tomcat selection dialog opens in the Documents folder. 
*  - The Tomcat path is stored using `java.util.prefs` (the Windows Registry or `~/.java` on Linux) —
*    no files are created on the Desktop or in the application's directory. 
*/
