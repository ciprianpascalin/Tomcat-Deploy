/*
 * TomcatDeploy - Tomcat Control Panel & Live Deploy (Java 17+, Swing)
 *
 * Rulare:   java TomcatDeploy.java            (sau compilat: javac TomcatDeploy.java && java TomcatDeploy)
 *           Windows, fără fereastră de consolă:  javaw TomcatDeploy.java   (cu "java" programul se repornește
 *           singur cu javaw și eliberează consola; păstrarea ei: -Dtd.console=true)
 * Necesită: JDK 17+ (nu doar JRE - compilarea Java se face în program cu javax.tools).
 *
 * Ce face:
 *  - Windows + Linux (+ macOS): scripturi catalina.bat / catalina.sh, separatori de cale, comenzi Maven.
 *  - Java pentru Tomcat și Maven se alege din program (lista JDK-urilor găsite + "Alt JDK..."), la fel și
 *    HotswapAgent (.jar); ambele locații sunt ținute minte. Din ele programul scrie singur bin/setenv.bat
 *    (Windows) sau bin/setenv.sh (Linux/macOS): JAVA_HOME + CATALINA_OPTS=-javaagent:...=autoHotswap=true.
 *    Un setenv scris de mână e păstrat ca setenv.*.bak.
 *  - Butonul "Configurează Tomcat": pe lângă setenv, pune modificationTestInterval=0 la servlet-ul jsp
 *    (conf/web.xml) și <Resources cachingAllowed="false"/> (conf/context.xml) - un JSP salvat se vede la
 *    primul refresh. Restul fișierelor rămâne neatins; originalele rămân ca .bak.
 *  - Tray: la minimizare fereastra se ascunde lângă ceas (fără programe gen RBTray). Iconița arată starea
 *    (verde = Tomcat pornit, roșu = eroare de compilare), iar cu fereastra ascunsă eroarea apare ca notificare.
 *    Pe Linux iconița se pune prin D-Bus (StatusNotifierItem - GNOME cu AppIndicator, Zorin, Ubuntu, KDE,
 *    Cinnamon, XFCE), fiindcă tray-ul clasic pe care îl știe Java nu mai există pe aceste desktopuri.
 *  - Aspect: pe Linux o temă proprie, compactă, întunecată sau deschisă după tema desktopului (aspectul GTK al
 *    Java umflă butoanele și rândurile); pe Windows aspectul sistemului. Se schimbă din lista "Aspect".
 *  - La "Pornește Deploy" curăță complet aplicația veche din Tomcat (webapps/<context>, <context>.war și
 *    cache-ul work/ al lui Tomcat), după o confirmare (se poate opri din bifă). Contextul implicit e ROOT;
 *    cu alt nume poți ține mai multe proiecte în același Tomcat.
 *  - Modificările se scriu PE LOC: fișierul din Tomcat nu e șters și recreat, ci se schimbă doar bucățile
 *    care diferă (și nu se scrie nimic dacă fișierul e identic).
 *  - Resursele respectă pom.xml: foldere de resurse, include/exclude și filtrare ${...} / @...@.
 *  - Reacție instant: folosește evenimentele sistemului de fișiere (inotify / ReadDirectoryChangesW) și le
 *    procesează pe loc, fără așteptare; un fișier web ajunge în Tomcat în ~1 ms, iar un .java e recompilat de
 *    obicei în 10-20 ms (context de compilare ținut cald per fișier + unul de rezervă pregătit în fundal).
 *    Clasele rămase identice nu sunt rescrise, deci HotswapAgent nu le reîncarcă degeaba.
 *  - Reluare: dacă proiectul, Tomcat-ul și contextul sunt aceleași ca la ultimul deploy reușit, NU se mai
 *    curăță și NU se mai rulează Maven; se sincronizează doar ce s-a schimbat între timp.
 *  - Buton "Deschide în browser"; context gol = ROOT (webapps/ROOT).
 *  - Dacă se schimbă dependențele în pom.xml, WEB-INF/lib se actualizează singur (prin Maven).
 *  - Generează META-INF ca după "mvn clean package": MANIFEST.MF + META-INF/maven/<groupId>/<artifactId>/
 *    (pom.xml + pom.properties), citite din pom.xml.
 *  - Multi-thread: scanner (1 thread), copiere fișiere în paralel (pool), compilare Java (thread dedicat),
 *    pornire/oprire Tomcat și Maven în fundal. Interfața nu îngheață niciodată.
 *  - Dialogul pentru proiect se deschide pe Desktop, cel pentru Tomcat în Documents.
 *  - Calea Tomcat e ținută minte cu java.util.prefs (registry pe Windows, ~/.java pe Linux) -
 *    NU se creează niciun fișier pe Desktop sau lângă program.
 */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileSystemView;
import javax.swing.text.BadLocationException;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.SimpleJavaFileObject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TomcatDeploy extends JFrame {

  // ===================================================================================
  // Constante
  // ===================================================================================
  static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  /**
   * Cât "stă liniștit" un .java înainte să-l compilăm (evită compilarea la jumătatea salvării). Cât mai mic =
   * mai rapid; dacă un editor scrie fișierul în mai mulți pași, următorul eveniment declanșează oricum o
   * recompilare corectă.
   */
  static final int JAVA_DEBOUNCE_MS = 10;
  /**
   * Cât așteptăm după dispariția unui fișier înainte să-l ștergem din Tomcat: editoarele care salvează atomic
   * (IntelliJ, vim, gedit, Kate) îl șterg/redenumesc și îl recreează imediat.
   */
  static final int DELETE_GRACE_MS = 80;
  /** Cât așteptăm conținutul unui fișier găsit trunchiat la 0 octeți (salvare pe loc prinsă la jumătate). */
  static final int EMPTY_GRACE_MS = 60;
  /** Cu evenimente de sistem active: scanare completă periodică, doar ca plasă de siguranță. */
  static final int POLL_SAFETY_MS = 1500;
  /** Până unde se rărește scanarea de siguranță cât timp evenimentele de sistem se dovedesc de încredere. */
  static final int POLL_SAFETY_MAX_MS = 15_000;
  /** Fără evenimente de sistem (ex: macOS): sondăm des. */
  static final int POLL_ONLY_MS = 150;
  /** Folosit doar dacă nu găsim deloc versiunea pluginului în Maven / pom / ~/.m2. */
  static final String DEFAULT_WAR_PLUGIN = "3.4.0";

  static final String PREF_TOMCAT = "tomcatHome";
  static final String PREF_PROJECT = "projectDir";
  static final String PREF_CONTEXT = "context";
  static final String PREF_CONFIRM = "confirmDelete";
  /** "proiect\ntomcat\ncontext" al ultimului deploy terminat cu succes (pentru reluare fără Maven). */
  static final String PREF_DEPLOYED = "lastDeploy";
  static final String PREF_DEPS_HASH = "depsHash";
  static final String PREF_WIDTH = "winWidth";
  static final String PREF_HEIGHT = "winHeight";
  /** JDK-ul ales pentru Tomcat și Maven (devine JAVA_HOME în setenv). */
  static final String PREF_JDK = "jdkHome";
  /** JDK-urile alese vreodată cu "Alt JDK..." (câte unul pe linie), ca să rămână în listă. */
  static final String PREF_JDK_LIST = "jdkList";
  static final String PREF_AGENT = "hotswapAgent";
  static final String PREF_AGENT_ON = "hotswapOn";
  static final String PREF_JVM_OPTS = "jvmOpts";
  static final String PREF_TRAY = "minimizeToTray";
  static final String PREF_TRAY_HINT = "trayHintShown";
  /** Aspectul ferestrei: auto | dark | light | system. */
  static final String PREF_THEME = "theme";
  static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

  /** Opțiunile cerute de HotswapAgent pe Java 9+ (cele din setenv.sh-ul scris până acum de mână, pe Linux). */
  static final String AGENT_OPENS =
      "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED"
          + " --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/jdk.internal.loader=ALL-UNNAMED";
  /** Textul după care recunoaștem un setenv scris de program (unul scris de mână e păstrat ca .bak). */
  static final String SETENV_MARK = "Generat de TomcatDeploy";

  /** Fișierele mai mici de atât se compară în memorie, bucată cu bucată; cele mari se scriu în flux, tot pe loc. */
  static final long MAX_IN_MEMORY = 16L * 1024 * 1024;
  /** Dimensiunea bucății la compararea unui fișier cu varianta din Tomcat. */
  static final int CHUNK = 64 * 1024;
  /** Delimitatori de filtrare Maven: ${cheie} și @cheie@ */
  static final Pattern FILTER_TOKEN = Pattern.compile("\\$\\{([^}\\s]+)}|@([^@\\s]+)@");
  /** Maven nu filtrează aceste tipuri; le adăugăm și pe cele binare uzuale. */
  static final Set<String> NON_FILTERED =
      Set.of("jpg", "jpeg", "gif", "bmp", "png", "ico", "pdf", "zip", "jar", "class", "woff", "woff2", "ttf", "otf", "eot");
  static final Set<String> SKIP_DIRS = Set.of("target", ".git", ".vscode", ".idea", "node_modules");
  static final Set<String> STANDARD_WEBAPPS = Set.of("ROOT", "docs", "examples", "host-manager", "manager");
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  /** "--- war:3.4.0:exploded (default-cli) @ demo ---" (Maven 3.9+) sau "--- maven-war-plugin:3.3.2:exploded ..." */
  static final Pattern WAR_VERSION =
      Pattern.compile("---\\s+(?:maven-)?war(?:-plugin)?:([^:\\s]+):exploded");
  static final Pattern MAVEN_VERSION = Pattern.compile("^Apache Maven (\\S+)");

  // ===================================================================================
  // Interfață
  // ===================================================================================
  private final JButton btnProject = new JButton("Selectează Proiectul Java");
  private final JLabel lblProject = new JLabel("Niciun proiect selectat");
  private final JButton btnTomcat = new JButton("Selectează Folder Tomcat");
  private final JLabel lblTomcat = new JLabel("Niciun folder Tomcat selectat");
  private final JButton btnStartTomcat = new JButton("Pornește Tomcat");
  private final JButton btnStopTomcat = new JButton("Oprește Tomcat");
  private final JButton btnBrowser = new JButton("Deschide în browser");
  private final JLabel lblName = new JLabel("Nume proiect: -");
  private final JLabel lblMaven = new JLabel("Maven: -");
  private final JLabel lblTarget = new JLabel("Destinație Tomcat: -");
  private final JCheckBox chkOthers =
      new JCheckBox("Șterge și alte aplicații din webapps (păstrez ROOT, docs, examples, manager, host-manager)");
  private final JTextField txtContext = new JTextField("", 14);
  private final JCheckBox chkConfirm = new JCheckBox("Cere confirmare înainte să șterg aplicația veche din Tomcat", true);
  private final JCheckBox chkFull =
      new JCheckBox("Reconstruiește de la zero (curăț + Maven) chiar dacă proiectul e același cu la ultimul deploy");
  private final JButton btnDeploy = new JButton("Pornește Deploy & Watcher Live");
  private final JTextArea logArea = new JTextArea();
  private final javax.swing.JComboBox<JdkItem> cmbJdk = new javax.swing.JComboBox<>();
  private final JButton btnJdk = new JButton("Alt JDK...");
  private final JButton btnAgent = new JButton("Selectează HotswapAgent (.jar)");
  private final JLabel lblAgent = new JLabel("Niciun HotswapAgent selectat");
  private final JCheckBox chkAgent = new JCheckBox("Pornește Tomcat cu HotswapAgent", true);
  private final JTextField txtJvmOpts = new JTextField("", 20);
  private final JButton btnConfigure = new JButton("Configurează Tomcat (setenv + reload rapid JSP)");
  private final JLabel lblConfig = new JLabel(" ");
  private final JCheckBox chkTray = new JCheckBox("La minimizare ascunde fereastra în tray (lângă ceas)", true);
  private final javax.swing.JComboBox<String> cmbTheme = new javax.swing.JComboBox<>();
  private final JLabel lblJava = new JLabel("Java pentru Tomcat și Maven:");
  private final JLabel lblOpts = new JLabel("Opțiuni JVM în plus (CATALINA_OPTS):");

  // ===================================================================================
  // Thread-uri
  // ===================================================================================
  /** 1 thread: scanează sursele la fiecare SCAN_INTERVAL_MS. */
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(daemon("scan"));
  /** N thread-uri: copiere fișiere în paralel. */
  private final ExecutorService copyPool =
      Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()), daemon("copy"));
  /** 1 thread: compilări Java, una după alta (nu scriem două compilări simultan în același folder). */
  private final ExecutorService compileExec = Executors.newSingleThreadExecutor(daemon("javac"));
  /** Pornire/oprire Tomcat, Maven, etc. */
  private final ExecutorService bg = Executors.newCachedThreadPool(daemon("bg"));

  // ===================================================================================
  // Stare
  // ===================================================================================
  private final Preferences prefs = Preferences.userNodeForPackage(TomcatDeploy.class);

  private volatile Path projectDir;
  private volatile Path tomcatHome;
  /** JDK-ul ales în program (JAVA_HOME pentru Tomcat și Maven) și versiunea lui majoră (0 = necunoscută). */
  private volatile Path jdkHome;
  private volatile int jdkFeature;
  /** HotswapAgent ales (.jar), bifa lui și opțiunile JVM în plus - oglindite aici ca să poată fi citite din orice thread. */
  private volatile Path agentJar;
  private volatile boolean agentOn = true;
  private volatile String jvmOpts = "";
  private boolean jdkUpdating;
  private volatile boolean deployRunning;
  private volatile boolean preparing;
  private volatile Process tomcatProcess;
  private volatile ScheduledFuture<?> watchTask;

  // sesiunea de deploy curentă
  private volatile String context = "ROOT"; // numele contextului = folderul din webapps
  private volatile Path target; // tomcat/webapps/<context>
  private volatile Path classesDir; // <context>/WEB-INF/classes
  private volatile Path javaSrc; // proiect/src/main/java
  private volatile PomInfo pom;
  private volatile int pomRelease;
  private volatile String warVersion;
  private volatile String mavenVersion;
  private volatile long pomMtime;
  private SyncRoot webRoot;
  private volatile List<SyncRoot> resRoots = List.of();
  private volatile JavacSession javac;
  // evenimente de sistem (inotify / ReadDirectoryChangesW)
  private volatile WatchService watchService;
  private volatile boolean watching;
  private volatile boolean nativeEvents;
  private final Map<WatchKey, Path> watchDirs = new ConcurrentHashMap<>();
  private final Set<Path> dirtyFiles = ConcurrentHashMap.newKeySet();
  private volatile boolean needFullScan;
  private volatile boolean pomDirty;
  /** Căi dispărute -> momentul (nanoTime) de la care ștergerea e socotită definitivă. */
  private final Map<Path, Long> pendingDeletes = new ConcurrentHashMap<>();
  /** Foldere nou apărute: le parcurgem o dată (doar pe ele). */
  private final Set<Path> newDirs = ConcurrentHashMap.newKeySet();
  /** Fișiere găsite trunchiate la 0 octeți -> până când așteptăm conținutul. Doar sub tickLock. */
  private final Map<Path, Long> emptyDefer = new HashMap<>();
  /** Un singur tick la un moment dat (thread-ul de evenimente și cel "scan" nu procesează în paralel). */
  private final Object tickLock = new Object();
  /** Momentul (nanoTime) celui mai apropiat tick programat. */
  private final java.util.concurrent.atomic.AtomicLong tickDue = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
  /** Cel puțin o modificare reală a venit prin evenimentele de sistem (deci funcționează pe acest disc). */
  private volatile boolean eventsProven;
  /** Scanarea a găsit o modificare pe care evenimentele nu au anunțat-o: nu mai rărim scanarea. */
  private volatile boolean eventsMissed;
  /** Fișiere găsite modificate de scanarea periodică; dispar de aici când sosește și evenimentul lor. */
  private final Map<Path, Long> scanFound = new ConcurrentHashMap<>();
  private volatile long safetyDelay = POLL_SAFETY_MS;
  /** Crește la fiecare pornire de deploy (ca o scanare programată de sesiunea veche să nu mai ruleze). */
  private volatile int deployGen;
  private volatile String depsFingerprint = "";
  private final Map<Path, Stamp> javaMtimes = new ConcurrentHashMap<>();
  private final Map<Path, Long> pending = new ConcurrentHashMap<>();

  // ===================================================================================
  // Pornire
  // ===================================================================================
  /**
   * Pool-ul de compilare cere --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED. Din JAR îl pune
   * manifestul (Add-Exports); la "java TomcatDeploy.java" sau "-cp" programul se relansează o singură dată cu flag-ul.
   * Dezactivare: -Dtd.norelaunch=true. Orice eșec -> rulează normal, doar mai lent la compilare.
   */
  private static void relaunchWithExports(String[] args) {
    try {
      if (Boolean.getBoolean("td.norelaunch") || System.getProperty("td.child") != null) return;
      String javaBin = ProcessHandle.current().info().command().orElse(null);
      if (javaBin == null) return;
      // Windows: pornit cu java.exe rămâne o fereastră neagră de consolă. Repornim o singură dată cu javaw.exe și
      // eliberăm consola (nu mai e nevoie de RBTray ca s-o ascunzi). Păstrarea consolei: -Dtd.console=true.
      String javaw = null;
      if (WINDOWS && !Boolean.getBoolean("td.console")) {
        Path jb = Path.of(javaBin);
        Path w = jb.resolveSibling("javaw.exe");
        if (jb.getFileName().toString().equalsIgnoreCase("java.exe") && Files.isRegularFile(w)) javaw = w.toString();
      }
      boolean needExports = !Boolean.getBoolean("td.nopool") && !TaskPool.available();
      if (!needExports && javaw == null) return;
      List<String> target = new ArrayList<>();
      java.security.CodeSource cs = TomcatDeploy.class.getProtectionDomain().getCodeSource();
      Path loc = cs == null || cs.getLocation() == null ? null : Path.of(cs.getLocation().toURI());
      String lname = loc == null ? "" : loc.toString().toLowerCase(Locale.ROOT);
      if (loc != null && Files.exists(loc) && (Files.isDirectory(loc) || lname.endsWith(".jar"))) {
        target.addAll(List.of("-cp", loc.toString(), "TomcatDeploy"));
      } else {
        String sun = System.getProperty("sun.java.command", "");
        String first = "";
        for (String tok : sun.trim().split("\\s+")) {
          if (tok.toLowerCase(Locale.ROOT).endsWith(".java")) {
            first = tok;
            break;
          }
        }
        if (first.isEmpty() || !Files.isRegularFile(Path.of(first))) return;
        target.add(first); // mod sursă: java --add-exports=... TomcatDeploy.java
      }
      List<String> cmd = new ArrayList<>();
      cmd.add(javaw != null ? javaw : javaBin);
      for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
        if (!a.startsWith("-agentlib") && !a.startsWith("-javaagent")) cmd.add(a);
      }
      if (needExports) cmd.add("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
      cmd.add("-Dtd.child=1");
      cmd.addAll(target);
      cmd.addAll(Arrays.asList(args));
      if (javaw != null) {
        // proces independent, fără consolă: dacă nu moare imediat, procesul acesta (și consola lui) se poate închide
        Process child =
            new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!child.waitFor(1200, TimeUnit.MILLISECONDS) || child.exitValue() == 0) System.exit(0);
        return; // nu a pornit: rulăm în procesul curent
      }
      Process child = new ProcessBuilder(cmd).inheritIO().start();
      Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
      System.exit(child.waitFor());
    } catch (Exception | LinkageError ignored) {
      // rulăm în procesul curent
    }
  }

  public static void main(String[] args) {
    relaunchWithExports(args);
    SwingUtilities.invokeLater(
        () -> {
          applyTheme(Preferences.userNodeForPackage(TomcatDeploy.class).get(PREF_THEME, "auto"));
          new TomcatDeploy().setVisible(true);
        });
  }

  private TomcatDeploy() {
    super("Tomcat Control Panel & Live Deploy");
    buildUi();
    loadSavedJava();
    loadSavedTomcat();
    loadSavedProject();
    initTray();
    bg.submit(TomcatDeploy::warmUpCompiler); // prima compilare reală nu mai plătește ~1s de "pornire la rece"
    bg.submit(this::discoverJdks); // caută JDK-urile instalate, fără să întârzie apariția ferestrei
    statusExec.scheduleWithFixedDelay(this::refreshTomcatStatus, 1500, STATUS_POLL_MS, TimeUnit.MILLISECONDS);
  }

  static ThreadFactory daemon(String name) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, name + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  // ===================================================================================
  // UI
  // ===================================================================================
  private void buildUi() {
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    setSize(
        Math.min(screen.width, Math.max(720, prefs.getInt(PREF_WIDTH, 980))),
        Math.min(screen.height, Math.max(540, prefs.getInt(PREF_HEIGHT, 800))));
    setLocationRelativeTo(null);

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            savePrefs();
            removeTrayIcon();
          }

          @Override
          public void windowIconified(WindowEvent e) {
            if (System.currentTimeMillis() < ignoreIconifyUntil) return; // tocmai o readucem din tray
            if (trayReady() && chkTray.isSelected()) hideToTray(); // în loc de bara de activități: lângă ceas
          }
        });
    txtContext.setText(prefs.get(PREF_CONTEXT, ""));
    txtContext.setToolTipText("Lasă gol pentru ROOT (aplicația răspunde direct la http://localhost:8080/)");
    chkConfirm.setSelected(prefs.getBoolean(PREF_CONFIRM, true));
    txtContext
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                refreshTarget();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                refreshTarget();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                refreshTarget();
              }
            });
    btnProject.addActionListener(e -> selectProject());
    btnTomcat.addActionListener(e -> selectTomcat());
    btnStartTomcat.addActionListener(e -> startTomcat());
    btnStopTomcat.addActionListener(e -> stopTomcat());
    btnBrowser.addActionListener(e -> openInBrowser());
    btnBrowser.setEnabled(false);
    btnDeploy.addActionListener(e -> toggleDeploy());
    btnStartTomcat.setEnabled(false);
    btnStopTomcat.setEnabled(false);
    btnDeploy.setEnabled(false);

    JPanel serverRow = new JPanel(new GridLayout(1, 3, 8, 0));
    serverRow.add(btnStartTomcat);
    serverRow.add(btnStopTomcat);
    serverRow.add(btnBrowser);

    // Rândurile stau la înălțimea lor firească: o grilă le-ar întinde pe toate cât cel mai înalt element (cu unele teme
    // de Linux butoanele sunt foarte înalte și log-ul nu mai avea loc pe ecran).
    JPanel contextRow = new JPanel(new BorderLayout(12, 0));
    contextRow.add(row(new JLabel("Nume context (gol = ROOT):"), txtContext), BorderLayout.WEST);
    contextRow.add(lblTarget, BorderLayout.CENTER);
    JPanel nameRow = new JPanel(new BorderLayout(24, 0));
    nameRow.add(lblName, BorderLayout.WEST);
    nameRow.add(lblMaven, BorderLayout.CENTER);
    JPanel info = stack("Detalii deploy", 3, nameRow, contextRow, chkOthers, chkConfirm, chkFull);

    JPanel deployRow = new JPanel(new BorderLayout());
    deployRow.add(btnDeploy, BorderLayout.CENTER);

    // Java + HotswapAgent + configurarea Tomcat: tot ce se scria până acum de mână în setenv / web.xml / context.xml
    cmbJdk.setPrototypeDisplayValue(new JdkItem(Path.of("x"), "17.0.00", 17));
    cmbJdk.setToolTipText("JDK-ul cu care pornește Tomcat și rulează Maven (JAVA_HOME). Alegerea e ținută minte.");
    cmbJdk.addActionListener(e -> jdkChosen());
    btnJdk.setToolTipText("Alege folderul unui JDK care nu apare în listă");
    btnJdk.addActionListener(e -> browseJdk());
    btnAgent.addActionListener(e -> selectAgent());
    chkAgent.setToolTipText("Adaugă -javaagent:<jar>=autoHotswap=true în CATALINA_OPTS (clasele recompilate se încarcă fără restart)");
    chkAgent.addActionListener(
        e -> {
          agentOn = chkAgent.isSelected();
          prefs.putBoolean(PREF_AGENT_ON, agentOn);
          flushPrefs();
          envChanged();
        });
    txtJvmOpts.setToolTipText("Opțional: alte opțiuni JVM pentru Tomcat, adăugate în CATALINA_OPTS (ex: --add-opens=..., -Xmx1g)");
    txtJvmOpts.addActionListener(e -> jvmOptsEdited());
    txtJvmOpts.addFocusListener(
        new java.awt.event.FocusAdapter() {
          @Override
          public void focusLost(java.awt.event.FocusEvent e) {
            jvmOptsEdited();
          }
        });
    btnConfigure.setToolTipText(
        "Scrie bin/setenv, pune modificationTestInterval=0 la servlet-ul jsp (conf/web.xml) și"
            + " <Resources cachingAllowed=\"false\"/> (conf/context.xml). Originalele rămân ca .bak.");
    btnConfigure.addActionListener(e -> configureTomcat());
    btnConfigure.setEnabled(false);
    JPanel jdkRow = new JPanel(new BorderLayout(10, 0));
    jdkRow.add(lblJava, BorderLayout.WEST);
    jdkRow.add(cmbJdk, BorderLayout.CENTER);
    jdkRow.add(btnJdk, BorderLayout.EAST);
    JPanel agentRow = row(btnAgent, lblAgent);
    agentRow.add(chkAgent, BorderLayout.EAST);
    JPanel env =
        stack("Java, HotswapAgent și configurarea Tomcat", 5, jdkRow, agentRow, row(lblOpts, txtJvmOpts), row(btnConfigure, lblConfig));

    ScrollablePanel north = new ScrollablePanel();
    fillStack(north, 7, row(btnProject, lblProject), row(btnTomcat, lblTomcat), env, serverRow, info, deployRow);
    // Plasă de siguranță: dacă partea de sus nu încape (ecran mic, temă cu elemente uriașe), ea primește o bară de
    // derulare, iar log-ul rămâne mereu vizibil.
    JScrollPane northScroll =
        new JScrollPane(north, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
          @Override
          public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension d = super.getPreferredSize();
            int max = Math.max(220, (int) (TomcatDeploy.this.getHeight() * 0.70));
            if (d.height > max) d.height = max;
            return d;
          }
        };
    northScroll.setBorder(null);
    northScroll.getVerticalScrollBar().setUnitIncrement(24);

    logArea.setEditable(false);
    logArea.setLineWrap(true);
    logArea.setBackground(new Color(0x1E1E1E));
    logArea.setForeground(new Color(0x00FF00));
    JScrollPane scroll = new JScrollPane(logArea);
    scroll.setBorder(BorderFactory.createTitledBorder("Log-uri"));

    for (String[] t : THEMES) cmbTheme.addItem(t[1]);
    String savedTheme = prefs.get(PREF_THEME, "auto");
    for (int i = 0; i < THEMES.length; i++) if (THEMES[i][0].equals(savedTheme)) cmbTheme.setSelectedIndex(i);
    cmbTheme.setToolTipText("Automat: pe Linux o temă proprie, întunecată sau deschisă după tema desktopului; pe Windows aspectul sistemului");
    cmbTheme.addActionListener(e -> themeChosen());
    JPanel themeBox = new JPanel(new BorderLayout(8, 0));
    themeBox.add(new JLabel("Aspect:"), BorderLayout.WEST);
    themeBox.add(cmbTheme, BorderLayout.CENTER);
    JPanel south = new JPanel(new BorderLayout(12, 0));
    south.add(chkTray, BorderLayout.WEST);
    south.add(themeBox, BorderLayout.EAST);

    JPanel root = new JPanel(new BorderLayout(0, 8));
    root.setBorder(new EmptyBorder(10, 10, 8, 10));
    root.add(northScroll, BorderLayout.NORTH);
    root.add(scroll, BorderLayout.CENTER);
    root.add(south, BorderLayout.SOUTH);
    setContentPane(root);
    restyle();
  }

  /** Panou care, pus într-un JScrollPane, ține mereu lățimea zonei vizibile (derulare doar pe verticală). */
  private static final class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
    private static final long serialVersionUID = 1L;

    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
      return Math.max(24, r.height - 24);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  /** Așază rândurile unul sub altul, pe toată lățimea, fiecare la înălțimea lui firească. */
  private static void fillStack(JPanel p, int gap, JComponent... rows) {
    p.setLayout(new java.awt.GridBagLayout());
    java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
    c.gridx = 0;
    c.weightx = 1;
    c.fill = java.awt.GridBagConstraints.HORIZONTAL;
    c.anchor = java.awt.GridBagConstraints.NORTHWEST;
    for (int i = 0; i < rows.length; i++) {
      c.gridy = i;
      c.insets = new java.awt.Insets(i == 0 ? 0 : gap, 0, 0, 0);
      p.add(rows[i], c);
    }
  }

  private static JPanel stack(String title, int gap, JComponent... rows) {
    JPanel p = new JPanel();
    fillStack(p, gap, rows);
    p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), new EmptyBorder(2, 6, 6, 6)));
    return p;
  }

  /** Aceeași lățime pentru mai multe elemente (ca tot ce urmează după ele să înceapă aliniat). */
  private static void sameWidth(JComponent... cs) {
    int w = 0;
    for (JComponent c : cs) {
      c.setPreferredSize(null);
      w = Math.max(w, c.getPreferredSize().width);
    }
    for (JComponent c : cs) {
      c.setPreferredSize(new java.awt.Dimension(w, c.getPreferredSize().height));
      c.revalidate(); // rearanjează și rândul din care face parte
    }
  }

  // ===================================================================================
  // Aspect (temă)
  // ===================================================================================
  static final String[][] THEMES = {{"auto", "Automat"}, {"dark", "Întunecat"}, {"light", "Deschis"}, {"system", "Al sistemului"}};
  /** Culorile pe care le schimbăm în Nimbus pentru tema întunecată (restul le derivă el din acestea). */
  static final String[] THEME_KEYS = {
    "control", "info", "nimbusBase", "nimbusBlueGrey", "nimbusLightBackground", "text", "nimbusDisabledText", "nimbusFocus",
    "nimbusSelectedText", "nimbusSelectionBackground", "nimbusInfoBlue", "nimbusOrange", "nimbusRed", "nimbusGreen",
    "nimbusAlertYellow", "textForeground", "textBackground", "textHighlight", "textHighlightText", "textInactiveText",
    "controlText", "menuText", "infoText", "menu", "scrollbar", "desktop", "defaultFont"
  };
  private static volatile boolean darkTheme;

  static boolean isDark(Color c) {
    return c != null && 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue() < 128;
  }

  /** Desktopul (GNOME, Zorin, Cinnamon, KDE cu setări GTK...) folosește o temă întunecată? */
  static boolean desktopPrefersDark() {
    try {
      String env = System.getenv("GTK_THEME");
      if (env != null && env.toLowerCase(Locale.ROOT).contains("dark")) return true;
      Object t = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Net/ThemeName");
      if (t instanceof String name) return name.toLowerCase(Locale.ROOT).contains("dark");
      Process p =
          new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start();
      p.getOutputStream().close();
      if (p.waitFor(600, TimeUnit.MILLISECONDS)) {
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("dark");
      }
      p.destroyForcibly();
    } catch (Throwable ignored) {
      // necunoscut
    }
    return false;
  }

  static final Pattern DESKTOP_FONT = Pattern.compile("^(.*?)[ ,]+(\\d+(?:\\.\\d+)?)$");

  /** Fontul interfeței de pe desktop (ex: "Ubuntu 11" la 96 dpi -> 15 px), ca fereastra să arate ca restul aplicațiilor. */
  static Font desktopFont() {
    int px = 13;
    try {
      java.awt.Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
      double dpi = 96;
      if (tk.getDesktopProperty("gnome.Xft/DPI") instanceof Integer d && d > 0) dpi = d / 1024.0;
      if (tk.getDesktopProperty("gnome.Gdk/WindowScalingFactor") instanceof Integer k && k > 1) dpi /= k; // Java scalează singur
      if (dpi < 60 || dpi > 240) dpi = 96;
      Object fontName = System.getProperty("td.font"); // ex: -Dtd.font="Ubuntu 12" pentru alt font / altă mărime
      if (fontName == null) fontName = tk.getDesktopProperty("gnome.Gtk/FontName");
      if (fontName instanceof String name) {
        Matcher m = DESKTOP_FONT.matcher(name.trim());
        if (m.find()) {
          px = Math.max(12, Math.min(20, (int) Math.round(Double.parseDouble(m.group(2)) * dpi / 72.0)));
          String family = m.group(1).replaceAll("(?i)[ ,]+(Regular|Medium|Book|Roman|Normal)$", "").trim();
          Font f = new Font(family, Font.PLAIN, px);
          if (f.getFamily(Locale.ROOT).equalsIgnoreCase(family)) return f;
        }
      }
    } catch (Throwable ignored) {
      // fontul implicit
    }
    return new Font(Font.SANS_SERIF, Font.PLAIN, px);
  }

  /**
   * Instalează aspectul. "auto": pe Linux o temă proprie (Nimbus recolorat), întunecată sau deschisă după tema
   * desktopului - aspectul GTK al Java umflă butoanele și rândurile cu multe teme moderne; pe Windows și macOS aspectul
   * nativ. "system" = aspectul sistemului peste tot (cum era înainte).
   */
  static void applyTheme(String mode) {
    boolean nimbus;
    boolean dark;
    switch (mode == null ? "auto" : mode) {
      case "dark" -> {
        nimbus = true;
        dark = true;
      }
      case "light" -> {
        nimbus = true;
        dark = false;
      }
      case "system" -> {
        nimbus = false;
        dark = false;
      }
      default -> {
        nimbus = !WINDOWS && !MAC;
        dark = nimbus && desktopPrefersDark();
      }
    }
    for (String k : THEME_KEYS) UIManager.put(k, null); // curățăm ce a pus o temă anterioară
    try {
      if (nimbus) {
        if (dark) { // culorile se pun ca UIResource: altfel ar rămâne "lipite" de componente la schimbarea temei
          int bg = 0x2B2D30;
          int field = 0x1E1F22;
          int fg = 0xDFE1E5;
          String[] keys = {
            "control", "info", "nimbusBase", "nimbusBlueGrey", "nimbusLightBackground", "text", "nimbusDisabledText",
            "nimbusFocus", "nimbusSelectedText", "nimbusSelectionBackground", "nimbusInfoBlue", "nimbusOrange", "nimbusRed",
            "nimbusGreen", "nimbusAlertYellow", "textForeground", "textBackground", "textHighlight", "textHighlightText",
            "textInactiveText", "controlText", "menuText", "infoText", "menu", "scrollbar", "desktop"
          };
          int[] rgb = {
            bg, 0x393B40, 0x1D2B3A, 0x24272B, field, fg, 0x7A7E85,
            0x3574F0, 0xFFFFFF, 0x2E5FC1, 0x548AF7, 0xC77D14, 0xB3392B,
            0x5FAD65, 0xF2C55C, fg, field, 0x2E5FC1, 0xFFFFFF,
            0x7A7E85, fg, fg, fg, bg, bg, bg
          };
          for (int i = 0; i < keys.length; i++) UIManager.put(keys[i], new javax.swing.plaf.ColorUIResource(rgb[i]));
        }
        // Fontul desktopului: Nimbus îl citește din tabela temei (nu din cea a aplicației), deci îl punem acolo înainte ca
        // tema să fie instalată și folosită de vreo componentă.
        javax.swing.plaf.nimbus.NimbusLookAndFeel laf = new javax.swing.plaf.nimbus.NimbusLookAndFeel();
        laf.getDefaults().put("defaultFont", new javax.swing.plaf.FontUIResource(desktopFont()));
        UIManager.setLookAndFeel(laf);
      } else {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      }
    } catch (Exception | LinkageError e) {
      for (String k : THEME_KEYS) UIManager.put(k, null);
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
        // rămâne aspectul implicit
      }
    }
    darkTheme = isDark(UIManager.getColor("Panel.background"));
  }

  private Color mutedColor() {
    return darkTheme ? new Color(0x9AA0A6) : new Color(0x70757A);
  }

  private Color okColor() {
    return darkTheme ? new Color(0x81C784) : new Color(0x2E7D32);
  }

  private Color warnColor() {
    return darkTheme ? new Color(0xFFB74D) : new Color(0xC75300);
  }

  /** Reaplică tot ce depinde de temă: culorile textelor (lizibile și pe fundal închis), alinierile, fontul log-ului. */
  private void restyle() {
    lblProject.setForeground(projectDir == null ? mutedColor() : null);
    lblTomcat.setForeground(tomcatHome == null ? mutedColor() : null);
    lblAgent.setForeground(agentJar == null ? mutedColor() : null);
    lblTarget.setForeground(darkTheme ? new Color(0xF28B82) : new Color(0xD32F2F));
    Font base = UIManager.getFont("Label.font");
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(12, base == null ? 12 : base.getSize() - 1)));
    sameWidth(btnProject, btnTomcat);
    sameWidth(lblJava, lblOpts);
    sameWidth(btnAgent, btnConfigure);
    Path home = tomcatHome;
    if (home != null) bg.submit(() -> refreshConfigState(home));
    revalidate();
    repaint();
  }

  private void themeChosen() {
    String mode = THEMES[Math.max(0, cmbTheme.getSelectedIndex())][0];
    if (mode.equals(prefs.get(PREF_THEME, "auto"))) return;
    prefs.put(PREF_THEME, mode);
    flushPrefs();
    applyTheme(mode);
    SwingUtilities.updateComponentTreeUI(this);
    restyle();
  }

  private static JPanel row(JComponent left, JComponent center) {
    JPanel p = new JPanel(new BorderLayout(10, 0));
    p.add(left, BorderLayout.WEST);
    p.add(center, BorderLayout.CENTER);
    return p;
  }

  private final java.util.concurrent.ConcurrentLinkedQueue<String> logQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final AtomicBoolean logFlushQueued = new AtomicBoolean();

  /**
   * Poate fi apelată din orice thread. Liniile se adună într-o coadă și ajung în interfață grupat: o salvare în masă
   * (sute de linii) înseamnă o singură actualizare a ferestrei, nu câte una pentru fiecare linie.
   */
  void log(String message) {
    logQueue.add("[" + LocalTime.now().format(TIME) + "] " + message + "\n");
    if (logFlushQueued.compareAndSet(false, true)) SwingUtilities.invokeLater(this::flushLog);
  }

  private void flushLog() {
    logFlushQueued.set(false);
    StringBuilder sb = new StringBuilder();
    for (String s; (s = logQueue.poll()) != null; ) sb.append(s);
    if (sb.length() == 0) return;
    logArea.append(sb.toString());
    if (logArea.getLineCount() > 4000) { // nu lăsăm log-ul să crească la infinit
      try {
        logArea.replaceRange("", 0, logArea.getLineEndOffset(logArea.getLineCount() - 3000));
      } catch (BadLocationException ignored) {
        // nimic
      }
    }
    logArea.setCaretPosition(logArea.getDocument().getLength());
  }

  private void setTomcatUi(boolean on) {
    tomcatUp = on;
    SwingUtilities.invokeLater(
        () -> {
          btnStartTomcat.setText(on ? "Tomcat este pornit" : "Pornește Tomcat");
          updateTray();
        });
  }

  private void setDeployUi(boolean running) {
    SwingUtilities.invokeLater(
        () -> {
          btnDeploy.setEnabled(true);
          btnDeploy.setText(running ? "Oprește Deploy Live" : "Pornește Deploy & Watcher Live");
          btnProject.setEnabled(!running);
          btnTomcat.setEnabled(!running);
          chkOthers.setEnabled(!running);
          txtContext.setEnabled(!running);
          chkConfirm.setEnabled(!running);
          chkFull.setEnabled(!running);
          updateTray();
        });
  }

  // ===================================================================================
  // Selectare proiect / Tomcat
  // ===================================================================================
  /** JFileChooser al cărui câmp "Folder name" este golit la deschidere. */
  private static final class FolderChooser extends JFileChooser {
    @Override
    protected JDialog createDialog(Component parent) throws HeadlessException {
      JDialog d = super.createDialog(parent);
      d.addWindowListener(
          new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
              SwingUtilities.invokeLater(() -> clearNameField(FolderChooser.this));
              // L&F-ul mai setează uneori câmpul după deschidere -> mai golim o dată imediat după
              javax.swing.Timer t = new javax.swing.Timer(150, ev -> clearNameField(FolderChooser.this));
              t.setRepeats(false);
              t.start();
            }
          });
      return d;
    }
  }

  private static void clearNameField(Container c) {
    for (Component k : c.getComponents()) {
      if (k instanceof JTextField tf) {
        tf.setText("");
        return;
      }
      if (k instanceof Container cc) clearNameField(cc);
    }
  }

  private static JFileChooser chooser(String title, File start) {
    JFileChooser fc = new FolderChooser();
    fc.setCurrentDirectory(start);
    fc.setDialogTitle(title);
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    fc.setAcceptAllFileFilterUsed(false);
    return fc;
  }

  private static File chosenDir(JFileChooser fc) {
    File f = fc.getSelectedFile();
    return f != null ? f : fc.getCurrentDirectory();
  }

  private void selectProject() {
    JFileChooser fc = chooser("Selectează folderul proiectului Java", desktopDir());
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    setProject(chosenDir(fc).toPath(), true);
  }

  /** Setează proiectul și (opțional) îl memorează pentru data viitoare. */
  private void setProject(Path dir, boolean persist) {
    projectDir = dir;
    lblProject.setText(dir.toString());
    lblProject.setForeground(null); // culoarea textului din tema curentă (negrul nu se vede pe fundal închis)
    if (persist) {
      prefs.put(PREF_PROJECT, dir.toString());
      flushPrefs();
    }
    refreshInfo();
  }

  private void loadSavedProject() {
    String saved = prefs.get(PREF_PROJECT, null);
    if (saved == null) return;
    Path p = Path.of(saved);
    if (Files.isDirectory(p)) {
      setProject(p, false);
      log("[INFO] Proiect reținut din sesiunea anterioară: " + p);
    }
  }

  private void flushPrefs() {
    try {
      prefs.flush();
    } catch (BackingStoreException e) {
      log("[AVERTISMENT] Nu am putut salva setările: " + e.getMessage());
    }
  }

  /** Dimensiunea ferestrei, contextul și opțiunea de confirmare. */
  private void savePrefs() {
    prefs.putInt(PREF_WIDTH, getWidth());
    prefs.putInt(PREF_HEIGHT, getHeight());
    prefs.put(PREF_CONTEXT, txtContext.getText().trim()); // gol = ROOT
    prefs.putBoolean(PREF_CONFIRM, chkConfirm.isSelected());
    String jo = txtJvmOpts.getText().trim();
    jvmOpts = jo;
    if (jo.length() <= Preferences.MAX_VALUE_LENGTH) prefs.put(PREF_JVM_OPTS, jo);
    flushPrefs();
  }

  /**
   * Numele contextului ales (folderul din webapps) sau null dacă e invalid. Refuzăm "." și ".." ca să nu
   * ajungem vreodată să ștergem alt folder decât unul din webapps.
   */
  private String contextName() {
    String t = txtContext.getText().trim();
    if (t.isEmpty()) t = "ROOT";
    return t.matches("[\\w.#-]+") && !t.matches("\\.+") ? t : null;
  }

  private void selectTomcat() {
    JFileChooser fc = chooser("Selectează folderul Tomcat (ex: apache-tomcat-11.0.x)", documentsDir());
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path home = findTomcatHome(chosenDir(fc).toPath());
    if (home == null) {
      JOptionPane.showMessageDialog(
          this,
          "Folderul ales nu pare un Tomcat (nu găsesc bin/catalina.sh sau bin/catalina.bat).",
          "Tomcat negăsit",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    setTomcat(home, true);
  }

  /** Setează Tomcat-ul și (opțional) îl memorează pentru data viitoare. */
  private void setTomcat(Path home, boolean persist) {
    tomcatHome = home;
    lblTomcat.setText(home.toString());
    lblTomcat.setForeground(null);
    btnStartTomcat.setEnabled(true);
    btnStopTomcat.setEnabled(true);
    btnBrowser.setEnabled(true);
    btnConfigure.setEnabled(true);
    bg.submit(() -> refreshConfigState(home));
    if (persist) {
      prefs.put(PREF_TOMCAT, home.toString());
      try {
        prefs.flush();
      } catch (BackingStoreException e) {
        log("[AVERTISMENT] Nu am putut memora locația Tomcat: " + e.getMessage());
      }
      log("[INFO] Locația Tomcat a fost memorată: " + home);
    }
    refreshInfo();
  }

  private void loadSavedTomcat() {
    String saved = prefs.get(PREF_TOMCAT, null);
    if (saved == null) return;
    Path p = Path.of(saved);
    if (isTomcat(p)) {
      setTomcat(p, false);
      log("[INFO] Tomcat reținut din sesiunea anterioară: " + p);
    } else {
      log("[AVERTISMENT] Tomcat-ul memorat (" + saved + ") nu mai există. Selectează-l din nou.");
    }
  }

  private void refreshInfo() {
    Path project = projectDir;
    if (project != null) {
      lblName.setText("Nume proiect: " + project.getFileName());
      try {
        PomInfo info = PomInfo.read(project);
        lblMaven.setText(
            "Maven: " + info.groupId + ":" + info.artifactId + ":" + info.version + "   |   Java " + info.javaVersion);
      } catch (Exception e) {
        lblMaven.setText("Maven: pom.xml negăsit sau ilizibil (" + e.getClass().getSimpleName() + ")");
      }
    }
    refreshTarget();
    btnDeploy.setEnabled(project != null && tomcatHome != null && !preparing);
  }

  private void refreshTarget() {
    if (tomcatHome == null) return;
    String c = contextName();
    lblTarget.setText(
        c == null
            ? "Nume de context invalid (litere, cifre, _ . # -)"
            : "Destinație Tomcat: " + tomcatHome.resolve("webapps").resolve(c));
  }

  // ===================================================================================
  // Foldere speciale (Desktop / Documents) - Windows, Linux, macOS
  // ===================================================================================
  static File desktopDir() {
    if (WINDOWS) {
      File f = FileSystemView.getFileSystemView().getHomeDirectory(); // pe Windows = Desktop (inclusiv OneDrive)
      if (f != null && f.isDirectory()) return insideRealFolder(f);
    }
    return xdgDir("XDG_DESKTOP_DIR", "Desktop");
  }

  /**
   * Pe Windows, getHomeDirectory() e rădăcina virtuală a Desktop-ului (cu Galerie, Acest PC, Rețea...).
   * Căutăm intrarea "Desktop" din ea - exact obiectul pe care îl deschide dublu-click-ul - ca dialogul
   * să arate doar conținutul real al folderului.
   */
  static File insideRealFolder(File virtualRoot) {
    try {
      for (File c : FileSystemView.getFileSystemView().getFiles(virtualRoot, false)) {
        if (c != virtualRoot && c.isDirectory() && c.getPath().equalsIgnoreCase(virtualRoot.getPath())) return c;
      }
    } catch (RuntimeException ignored) {
      // folosim calea simplă
    }
    return new File(virtualRoot.getPath());
  }

  static File documentsDir() {
    if (WINDOWS) {
      File f = FileSystemView.getFileSystemView().getDefaultDirectory(); // pe Windows = Documents
      if (f != null && f.isDirectory()) return f;
    }
    return xdgDir("XDG_DOCUMENTS_DIR", "Documents");
  }

  /** Linux: citește ~/.config/user-dirs.dirs (merge și cu "Desktop"/"Birou" localizat). */
  static File xdgDir(String key, String fallbackName) {
    File home = new File(System.getProperty("user.home"));
    try {
      Path cfg = home.toPath().resolve(".config").resolve("user-dirs.dirs");
      if (Files.isRegularFile(cfg)) {
        for (String line : Files.readAllLines(cfg)) {
          line = line.trim();
          if (line.startsWith(key + "=")) {
            String v = line.substring(key.length() + 1).replace("\"", "").replace("$HOME", home.getPath());
            File f = new File(v);
            if (f.isDirectory()) return f;
          }
        }
      }
    } catch (IOException ignored) {
      // folosim fallback
    }
    File f = new File(home, fallbackName);
    return f.isDirectory() ? f : home;
  }

  // ===================================================================================
  // Tomcat: pornire / oprire
  // ===================================================================================
  static boolean isTomcat(Path p) {
    Path bin = p.resolve("bin");
    return Files.isRegularFile(bin.resolve("catalina.sh")) || Files.isRegularFile(bin.resolve("catalina.bat"));
  }

  /** Acceptă folderul Tomcat, un subfolder al lui (ex: bin), sau un folder care conține Tomcat-ul. */
  static Path findTomcatHome(Path chosen) {
    if (isTomcat(chosen)) return chosen;
    Path parent = chosen.getParent();
    if (parent != null && isTomcat(parent)) return parent;
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(chosen, p -> Files.isDirectory(p))) {
      Path best = null;
      for (Path c : ds) {
        if (isTomcat(c) && (best == null || c.getFileName().toString().compareTo(best.getFileName().toString()) > 0)) {
          best = c;
        }
      }
      return best;
    } catch (IOException e) {
      return null;
    }
  }

  /** Windows: cmd /c script ... ; Linux: sh script ... (merge și dacă scriptul nu are drept de execuție). */
  static List<String> scriptCommand(Path script, String... args) {
    List<String> cmd = new ArrayList<>();
    if (WINDOWS) {
      cmd.add("cmd.exe");
      cmd.add("/c");
    } else {
      cmd.add("sh");
    }
    cmd.add(script.toString());
    cmd.addAll(Arrays.asList(args));
    return cmd;
  }

  static boolean portOpen(int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Portul HTTP din conf/server.xml (implicit 8080). */
  static int httpPort(Path tomcat) {
    try {
      Document d = parseXml(tomcat.resolve("conf").resolve("server.xml"));
      NodeList nl = d.getElementsByTagName("Connector");
      for (int i = 0; i < nl.getLength(); i++) {
        Element e = (Element) nl.item(i);
        String proto = e.getAttribute("protocol");
        String port = e.getAttribute("port");
        if (!port.isEmpty() && !e.hasAttribute("SSLEnabled") && (proto.isEmpty() || proto.startsWith("HTTP"))) {
          return Integer.parseInt(port.trim());
        }
      }
    } catch (Exception ignored) {
      // implicit
    }
    return 8080;
  }

  private void startTomcat() {
    final Path home = tomcatHome;
    if (home == null) return;
    bg.submit(() -> startTomcatNow(home));
  }

  private static Path catalinaScript(Path home) {
    return home.resolve("bin").resolve(WINDOWS ? "catalina.bat" : "catalina.sh");
  }

  /** Pornește Tomcat și așteaptă să răspundă portul HTTP. Rulează pe un thread de fundal. @return true dacă e pornit */
  private boolean startTomcatNow(Path home) {
    final Path script = catalinaScript(home);
    if (!Files.isRegularFile(script)) {
      log("[EROARE] Nu găsesc " + script);
      return false;
    }
    Process running = tomcatProcess;
    if (running != null && running.isAlive()) {
      log("[TOMCAT] Pornirea este deja în curs / Tomcat rulează.");
      return false;
    }
    final int port = httpPort(home);
    if (portOpen(port)) {
      log("[TOMCAT] Portul " + port + " răspunde deja - Tomcat pare pornit.");
      setTomcatUi(true);
      return true;
    }
    try {
      syncSetenv(home, false); // JDK-ul și HotswapAgent-ul alese în program ajung în bin/setenv înainte de pornire
      Path agent = activeAgent(false);
      int jf = jdkFeature;
      log("[TOMCAT] Pornesc Tomcat în fundal" + (jdkHome != null ? " cu Java " + (jf > 0 ? String.valueOf(jf) : "aleasă") : "")
          + (agent != null ? " + HotswapAgent" : "") + "...");
      ProcessBuilder pb = new ProcessBuilder(scriptCommand(script, "run")).directory(script.getParent().toFile());
      applyTomcatEnv(pb, home);
      pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
      long t0 = System.nanoTime();
      Process p = pb.start();
      tomcatProcess = p;
      long deadline = t0 + 120_000_000_000L;
      while (System.nanoTime() < deadline) {
        if (portOpen(port)) {
          log("[OK] Tomcat rulează (port " + port + " activ, pornit în " + (System.nanoTime() - t0) / 1_000_000 + " ms).");
          setTomcatUi(true);
          return true;
        }
        if (!p.isAlive()) {
          log("[EROARE] Tomcat s-a oprit imediat (cod " + p.exitValue() + "). Vezi folderul logs/ din Tomcat.");
          return false;
        }
        Thread.sleep(200);
      }
      log("[AVERTISMENT] Tomcat a pornit, dar portul " + port + " nu răspunde încă.");
    } catch (Exception e) {
      log("[EROARE] La pornirea Tomcat: " + e.getMessage());
    }
    return false;
  }

  private void stopTomcat() {
    final Path home = tomcatHome;
    if (home == null) return;
    bg.submit(() -> stopTomcatNow(home));
  }

  /** Oprește Tomcat (catalina stop) și așteaptă eliberarea portului. @return true dacă s-a oprit */
  private boolean stopTomcatNow(Path home) {
    final Path script = catalinaScript(home);
    final int port = httpPort(home);
    try {
      log("[TOMCAT] Opresc Tomcat...");
      ProcessBuilder pb = new ProcessBuilder(scriptCommand(script, "stop")).directory(script.getParent().toFile());
      applyTomcatEnv(pb, home);
      pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.start().waitFor(30, TimeUnit.SECONDS);
      long deadline = System.nanoTime() + 30_000_000_000L;
      while (portOpen(port) && System.nanoTime() < deadline) Thread.sleep(200);
      if (portOpen(port)) {
        log("[AVERTISMENT] Comanda de oprire a fost trimisă, dar portul " + port + " încă răspunde.");
        return false;
      }
      Process p = tomcatProcess; // dacă l-am pornit noi, așteptăm și ieșirea procesului (ca o repornire să găsească porturile libere)
      if (p != null) p.waitFor(15, TimeUnit.SECONDS);
      log("[OK] Tomcat oprit.");
      setTomcatUi(false);
      return true;
    } catch (Exception e) {
      log("[EROARE] La oprirea Tomcat: " + e.getMessage());
      return false;
    }
  }

  /** JAVA_HOME pentru procesele pornite de program (Tomcat, Maven): JDK-ul ales; altfel cel din sistem / al programului. */
  private void applyJavaEnv(ProcessBuilder pb) {
    Map<String, String> env = pb.environment();
    Path jdk = jdkHome;
    if (jdk != null) {
      env.put("JAVA_HOME", jdk.toString());
      env.remove("JRE_HOME"); // altfel scripturile Tomcat l-ar prefera în locul lui JAVA_HOME
      return;
    }
    String jh = env.get("JAVA_HOME");
    if (jh == null || jh.isBlank()) env.put("JAVA_HOME", System.getProperty("java.home"));
  }

  private void applyTomcatEnv(ProcessBuilder pb, Path home) {
    applyJavaEnv(pb);
    Map<String, String> env = pb.environment();
    env.put("CATALINA_HOME", home.toString());
    env.put("CATALINA_BASE", home.toString()); // un CATALINA_BASE din sistem ar trimite Tomcat la alt conf/ și alt setenv
  }

  // -----------------------------------------------------------------------------------
  // Starea Tomcat-ului (pornit / oprit), urmărită și când e pornit sau oprit din afara programului
  // -----------------------------------------------------------------------------------
  static final int STATUS_POLL_MS = 4000;
  private final ScheduledExecutorService statusExec = Executors.newSingleThreadScheduledExecutor(daemon("status"));
  private volatile boolean tomcatUp;
  private Path portCacheHome;
  private long portCacheStamp;
  private int portCachePort;

  private void refreshTomcatStatus() {
    try {
      Path home = tomcatHome;
      if (home == null) return;
      boolean up = portOpen(cachedHttpPort(home));
      if (up != tomcatUp) setTomcatUi(up);
    } catch (Throwable ignored) {
      // doar informativ
    }
  }

  /** Portul HTTP, recitit din server.xml doar când fișierul se schimbă. */
  private synchronized int cachedHttpPort(Path home) {
    long stamp = stampOf(home.resolve("conf").resolve("server.xml"));
    if (!home.equals(portCacheHome) || stamp != portCacheStamp) {
      portCachePort = httpPort(home);
      portCacheHome = home;
      portCacheStamp = stamp;
    }
    return portCachePort;
  }

  // ===================================================================================
  // Java (JDK) pentru Tomcat și Maven + HotswapAgent: alese din program și ținute minte
  // ===================================================================================
  /** Un JDK din listă. */
  record JdkItem(Path home, String version, int feature) {
    @Override
    public String toString() {
      return "Java " + (version.isEmpty() ? "?" : version) + "   \u2014   " + home;
    }
  }

  static boolean isJavaHome(Path p) {
    Path bin = p.resolve("bin");
    return Files.isRegularFile(bin.resolve("java.exe")) || Files.isRegularFile(bin.resolve("java"));
  }

  static final Pattern VERSION_IN_NAME = Pattern.compile("(\\d+(?:[._]\\d+)*)");
  static final Pattern VERSION_HEAD = Pattern.compile("^(\\d{1,4})(?:\\.(\\d{1,4}))?");

  /** Versiunea din fișierul "release" al JDK-ului (JAVA_VERSION="17.0.21"); altfel ghicită din numele folderului. */
  static String javaVersionOf(Path home) {
    try {
      Path rel = home.resolve("release");
      if (Files.isRegularFile(rel)) {
        for (String line : Files.readAllLines(rel, StandardCharsets.ISO_8859_1)) {
          line = line.trim();
          if (line.startsWith("JAVA_VERSION=")) return line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // încercăm numele folderului
    }
    Path fn = home.getFileName();
    Matcher m = VERSION_IN_NAME.matcher(fn == null ? "" : fn.toString());
    return m.find() ? m.group(1) : "";
  }

  /** "17.0.21" -> 17, "1.8.0_402" -> 8, necunoscut -> 0. */
  static int javaFeature(String version) {
    Matcher m = VERSION_HEAD.matcher(version);
    if (!m.find()) return 0;
    int a = Integer.parseInt(m.group(1));
    return a == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : a;
  }

  static JdkItem jdkItem(Path home) {
    Path abs = home.toAbsolutePath().normalize();
    String v = javaVersionOf(abs);
    return new JdkItem(abs, v, javaFeature(v));
  }

  /** Cheie pentru dubluri: calea reală (două linkuri către același JDK apar o singură dată). */
  private static String realKey(Path p) {
    try {
      return p.toRealPath().toString();
    } catch (IOException e) {
      return p.toAbsolutePath().normalize().toString();
    }
  }

  static Path pathOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Path.of(s.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Folderele în care se instalează de obicei JDK-uri, plus Documents / folderul personal (unde le despachetezi tu). */
  static List<Path> jdkParents() {
    List<Path> parents = new ArrayList<>();
    if (WINDOWS) {
      for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
        Path pf = pathOrNull(System.getenv(env));
        if (pf == null) continue;
        parents.add(pf);
        for (String vendor :
            List.of("Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "BellSoft", "Zulu", "Amazon Corretto",
                "Semeru", "RedHat", "ojdkbuild", "OpenJDK", "JetBrains")) {
          parents.add(pf.resolve(vendor));
        }
      }
    } else {
      for (String d : List.of("/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/opt", "/usr/local", "/Library/Java/JavaVirtualMachines")) {
        parents.add(Path.of(d));
      }
    }
    Path h = pathOrNull(System.getProperty("user.home"));
    if (h != null) {
      parents.add(h.resolve(".jdks"));
      parents.add(h.resolve(".sdkman").resolve("candidates").resolve("java"));
      parents.add(h);
      parents.add(WINDOWS ? h.resolve("Documents") : xdgDir("XDG_DOCUMENTS_DIR", "Documents").toPath());
    }
    return parents;
  }

  /** JDK-urile găsite pe calculator (un nivel sub folderele uzuale). Citește doar atribute de fișiere, deci e rapid. */
  static List<Path> findJdks() {
    List<Path> out = new ArrayList<>();
    for (Path parent : jdkParents()) {
      try {
        if (!Files.isDirectory(parent)) continue;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
          for (Path c : ds) {
            if (isJavaHome(c)) {
              out.add(c);
            } else {
              Path mac = c.resolve("Contents").resolve("Home"); // pachetele .jdk de pe macOS
              if (isJavaHome(mac)) out.add(mac);
            }
          }
        }
      } catch (IOException | RuntimeException ignored) {
        // folder inaccesibil
      }
    }
    return out;
  }

  /** Căile reale ale JDK-urilor aflate deja în listă. Doar pe EDT. */
  private final Set<String> jdkKeys = new HashSet<>();

  /** Adaugă un JDK în listă dacă nu e deja; întoarce elementul din listă (cel nou sau cel existent). Doar pe EDT. */
  private JdkItem addJdk(JdkItem item, String key) {
    if (jdkKeys.add(key)) {
      jdkUpdating = true;
      try {
        cmbJdk.addItem(item);
      } finally {
        jdkUpdating = false;
      }
      return item;
    }
    for (int i = 0; i < cmbJdk.getItemCount(); i++) {
      JdkItem it = cmbJdk.getItemAt(i);
      if (realKey(it.home()).equals(key)) return it;
    }
    return item;
  }

  /** Face din {@code home} JDK-ul curent (îl adaugă în listă dacă lipsește). Doar pe EDT. */
  private void selectJdk(Path home, boolean persist) {
    JdkItem item = addJdk(jdkItem(home), realKey(home));
    jdkUpdating = true;
    try {
      cmbJdk.setSelectedItem(item);
    } finally {
      jdkUpdating = false;
    }
    jdkHome = item.home();
    jdkFeature = item.feature();
    if (!persist) return;
    prefs.put(PREF_JDK, item.home().toString());
    flushPrefs();
    log("[INFO] Java pentru Tomcat și Maven: " + item.home() + " (Java " + (item.version().isEmpty() ? "?" : item.version()) + ") - ținut minte.");
    if (!Files.isRegularFile(item.home().resolve("bin").resolve(WINDOWS ? "javac.exe" : "javac"))) {
      log("[AVERTISMENT] Folderul ales e un JRE (fără javac): Tomcat pornește cu el, dar Maven nu va putea compila.");
    }
    envChanged();
  }

  private void jdkChosen() {
    if (jdkUpdating) return;
    JdkItem it = (JdkItem) cmbJdk.getSelectedItem();
    if (it == null || it.home().equals(jdkHome)) return;
    selectJdk(it.home(), true);
  }

  /** Acceptă folderul JDK-ului, subfolderul lui bin sau (macOS) pachetul .jdk. */
  static Path findJavaHome(Path chosen) {
    if (isJavaHome(chosen)) return chosen;
    Path parent = chosen.getParent();
    if (parent != null && isJavaHome(parent)) return parent;
    Path mac = chosen.resolve("Contents").resolve("Home");
    return isJavaHome(mac) ? mac : null;
  }

  private void browseJdk() {
    Path cur = jdkHome;
    File start = cur != null && cur.getParent() != null ? cur.getParent().toFile() : documentsDir();
    JFileChooser fc = chooser("Selectează folderul JDK (cel care conține bin/java)", start);
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path home = findJavaHome(chosenDir(fc).toPath());
    if (home == null) {
      JOptionPane.showMessageDialog(
          this, "Folderul ales nu pare un JDK (nu găsesc bin/java).", "JDK negăsit", JOptionPane.WARNING_MESSAGE);
      return;
    }
    // îl ținem minte și în lista de JDK-uri alese de mână, ca să apară mereu
    java.util.LinkedHashSet<String> list = new java.util.LinkedHashSet<>();
    list.add(home.toAbsolutePath().normalize().toString());
    for (String s : prefs.get(PREF_JDK_LIST, "").split("\n")) if (!s.isBlank()) list.add(s.trim());
    prefs.put(PREF_JDK_LIST, list.stream().limit(12).collect(Collectors.joining("\n")));
    selectJdk(home, true);
  }

  /** La pornire: JDK-ul, HotswapAgent-ul și opțiunile ținute minte. */
  private void loadSavedJava() {
    // JDK-ul ales data trecută; altfel JAVA_HOME din sistem; altfel cel cu care rulează programul (ca până acum)
    String saved = prefs.get(PREF_JDK, null);
    Path chosen = null;
    if (saved != null) {
      Path p = pathOrNull(saved);
      if (p != null && isJavaHome(p)) chosen = p;
      else log("[AVERTISMENT] JDK-ul memorat (" + saved + ") nu mai există. Alege altul din listă.");
    }
    Path envHome = pathOrNull(System.getenv("JAVA_HOME"));
    Path own = pathOrNull(System.getProperty("java.home"));
    if (chosen == null && envHome != null && isJavaHome(envHome)) chosen = envHome;
    if (chosen == null && own != null && isJavaHome(own)) chosen = own;
    List<Path> first = new ArrayList<>();
    if (chosen != null) first.add(chosen);
    for (String line : prefs.get(PREF_JDK_LIST, "").split("\n")) {
      Path p = pathOrNull(line);
      if (p != null) first.add(p);
    }
    if (envHome != null) first.add(envHome);
    if (own != null) first.add(own);
    for (Path p : first) if (isJavaHome(p)) addJdk(jdkItem(p), realKey(p));
    if (chosen != null) {
      selectJdk(chosen, false);
      if (saved != null && jdkHome != null) log("[INFO] Java reținut din sesiunea anterioară: " + jdkHome);
    }

    agentOn = prefs.getBoolean(PREF_AGENT_ON, true);
    chkAgent.setSelected(agentOn);
    String a = prefs.get(PREF_AGENT, null);
    if (a != null) {
      Path p = pathOrNull(a);
      if (p != null && Files.isRegularFile(p)) {
        setAgent(p, false);
        log("[INFO] HotswapAgent reținut din sesiunea anterioară: " + p);
      } else {
        log("[AVERTISMENT] HotswapAgent-ul memorat (" + a + ") nu mai există. Selectează-l din nou.");
      }
    }
    jvmOpts = prefs.get(PREF_JVM_OPTS, WINDOWS ? "" : AGENT_OPENS).trim();
    txtJvmOpts.setText(jvmOpts);
    txtJvmOpts.setCaretPosition(0);
  }

  /** Caută în fundal JDK-urile instalate și le adaugă în listă (cele mai noi primele). */
  private void discoverJdks() {
    try {
      List<JdkItem> items = new ArrayList<>();
      Map<JdkItem, String> keys = new HashMap<>();
      for (Path p : findJdks()) {
        JdkItem it = jdkItem(p);
        if (keys.putIfAbsent(it, realKey(p)) == null) items.add(it);
      }
      items.sort((x, y) -> x.feature() != y.feature() ? Integer.compare(y.feature(), x.feature()) : x.home().compareTo(y.home()));
      SwingUtilities.invokeLater(
          () -> {
            for (JdkItem it : items) addJdk(it, keys.get(it));
          });
    } catch (Throwable ignored) {
      // lista rămâne cu JDK-ul curent; "Alt JDK..." merge oricum
    }
  }

  /** null dacă fișierul e un agent Java (jar cu Premain-Class în manifest); altfel explicația. */
  static String agentProblem(Path jar) {
    if (!Files.isRegularFile(jar)) return "Fișierul nu există:\n" + jar;
    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
      Manifest mf = jf.getManifest();
      if (mf == null || mf.getMainAttributes().getValue("Premain-Class") == null) {
        return "Fișierul ales nu este un agent Java (manifestul lui nu are Premain-Class):\n" + jar
            + "\n\nAlege hotswap-agent-x.y.z.jar.";
      }
      return null;
    } catch (IOException e) {
      return "Nu pot citi fișierul ca arhivă JAR:\n" + jar;
    }
  }

  private void selectAgent() {
    Path cur = agentJar;
    JFileChooser fc = new JFileChooser(cur != null && cur.getParent() != null ? cur.getParent().toFile() : documentsDir());
    fc.setDialogTitle("Selectează HotswapAgent (hotswap-agent-x.y.z.jar)");
    fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Fișiere JAR (*.jar)", "jar"));
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return;
    Path jar = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
    String problem = agentProblem(jar);
    if (problem != null) {
      JOptionPane.showMessageDialog(this, problem, "HotswapAgent", JOptionPane.WARNING_MESSAGE);
      return;
    }
    setAgent(jar, true);
  }

  /** Setează HotswapAgent-ul și (opțional) îl memorează pentru data viitoare. Doar pe EDT. */
  private void setAgent(Path jar, boolean persist) {
    agentJar = jar;
    // numele fișierului întâi: rămâne vizibil și când rândul e îngust (calea întreagă apare și la trecerea cu mouse-ul)
    lblAgent.setText(jar.getFileName() + "   \u2014   " + jar.getParent());
    lblAgent.setToolTipText(jar.toString());
    lblAgent.setForeground(null);
    if (!persist) return;
    prefs.put(PREF_AGENT, jar.toString());
    if (!chkAgent.isSelected()) {
      chkAgent.setSelected(true);
      agentOn = true;
      prefs.putBoolean(PREF_AGENT_ON, true);
    }
    flushPrefs();
    log("[INFO] HotswapAgent: " + jar + " - locație ținută minte.");
    envChanged();
  }

  private void jvmOptsEdited() {
    String v = txtJvmOpts.getText().trim();
    if (v.equals(jvmOpts)) return;
    jvmOpts = v;
    if (v.length() <= Preferences.MAX_VALUE_LENGTH) prefs.put(PREF_JVM_OPTS, v);
    flushPrefs();
    envChanged();
  }

  /** S-a schimbat JDK-ul / agentul / opțiunile: dacă Tomcat-ul are deja un setenv scris de program, îl aducem la zi. */
  private void envChanged() {
    final Path home = tomcatHome;
    if (home == null) return;
    bg.submit(
        () -> {
          if (setenvIsOurs(home)) syncSetenv(home, false);
          refreshConfigState(home);
        });
  }

  // ===================================================================================
  // Configurarea Tomcat din program: bin/setenv + reload rapid pentru JSP și resurse (conf/web.xml, conf/context.xml)
  // ===================================================================================
  static final Pattern SAFE_BAT = Pattern.compile("[A-Za-z0-9_\\-.:\\\\/=+,@~]+");
  static final Pattern SAFE_SH = Pattern.compile("[A-Za-z0-9_\\-.:/=+,@]+");

  /** Un argument pentru linia de comandă din catalina.bat: ghilimele doar dacă are spații sau caractere speciale. */
  static String batArg(String a) {
    return SAFE_BAT.matcher(a).matches() ? a : "\"" + a + "\"";
  }

  /** Un argument pentru catalina.sh (care trece CATALINA_OPTS prin eval): apostrofuri doar dacă e nevoie. */
  static String shArg(String a) {
    return SAFE_SH.matcher(a).matches() ? a : "'" + a.replace("'", "'\\''") + "'";
  }

  /** Escapare pentru interiorul ghilimelelor duble în sh. */
  static String shDq(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`");
  }

  /** Pe Java 8 și mai vechi, --add-opens / --add-exports / --add-modules ar opri pornirea JVM-ului: le scoatem. */
  static String optsFor(int feature, String opts) {
    if (opts == null || opts.isBlank()) return "";
    if (feature == 0 || feature >= 9) return opts.trim();
    StringBuilder sb = new StringBuilder();
    for (String tok : opts.trim().split("\\s+")) {
      if (tok.startsWith("--add-opens") || tok.startsWith("--add-exports") || tok.startsWith("--add-modules")) continue;
      sb.append(sb.length() > 0 ? " " : "").append(tok);
    }
    return sb.toString();
  }

  /**
   * Conținutul lui bin/setenv.bat (Windows) sau bin/setenv.sh (Linux/macOS) - exact ce se scria până acum de mână:
   * JAVA_HOME = JDK-ul ales în program, CATALINA_OPTS = -javaagent:&lt;HotswapAgent&gt;=autoHotswap=true (+ opțiunile în plus).
   */
  static String setenvContent(boolean windows, Path jdk, Path agent, String extraOpts) {
    StringBuilder opts = new StringBuilder();
    if (agent != null) {
      String a = "-javaagent:" + agent + "=autoHotswap=true";
      opts.append(windows ? batArg(a) : shArg(a));
    }
    String extra = extraOpts == null ? "" : extraOpts.trim().replaceAll("[\\r\\n]+", " ");
    if (!extra.isEmpty()) opts.append(opts.length() > 0 ? " " : "").append(extra);
    StringBuilder sb = new StringBuilder();
    if (windows) {
      sb.append("@echo off\r\n");
      String body = (jdk == null ? "" : jdk.toString()) + opts;
      if (!StandardCharsets.US_ASCII.newEncoder().canEncode(body)) sb.append("chcp 65001 >nul\r\n"); // căi cu diacritice
      sb.append("rem ").append(SETENV_MARK).append(" - se rescrie singur din alegerile facute in program\r\n");
      if (jdk != null) sb.append("set \"JAVA_HOME=").append(jdk.toString().replace("%", "%%")).append("\"\r\n");
      if (opts.length() > 0) sb.append("set \"CATALINA_OPTS=").append(opts.toString().replace("%", "%%")).append("\"\r\n");
    } else {
      sb.append("#!/bin/sh\n");
      sb.append("# ").append(SETENV_MARK).append(" - se rescrie singur din alegerile facute in program\n");
      if (jdk != null) sb.append("export JAVA_HOME=\"").append(shDq(jdk.toString())).append("\"\n");
      if (opts.length() > 0) sb.append("export CATALINA_OPTS=\"").append(shDq(opts.toString())).append("\"\n");
    }
    return sb.toString();
  }

  private static Path setenvFile(Path tomcat) {
    return tomcat.resolve("bin").resolve(WINDOWS ? "setenv.bat" : "setenv.sh");
  }

  static boolean setenvIsOurs(Path tomcat) {
    try {
      Path f = setenvFile(tomcat);
      return Files.isRegularFile(f) && new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains(SETENV_MARK);
    } catch (IOException e) {
      return false;
    }
  }

  /** Agentul de folosit acum (null = fără): bifat, ales și încă existent pe disc. */
  private Path activeAgent(boolean warn) {
    Path a = agentJar;
    if (!agentOn || a == null) return null;
    if (Files.isRegularFile(a)) return a;
    if (warn) log("[AVERTISMENT] HotswapAgent nu mai există la " + a + " - Tomcat va porni fără el. Selectează-l din nou.");
    return null;
  }

  /** Ce ar trebui să conțină acum setenv (null = nimic de pus: fără JDK ales, fără agent, fără opțiuni). */
  private byte[] wantedSetenv(boolean warn) {
    Path jdk = jdkHome;
    Path agent = activeAgent(warn);
    String opts = optsFor(jdkFeature, jvmOpts);
    if (jdk == null && agent == null && opts.isEmpty()) return null;
    return setenvContent(WINDOWS, jdk, agent, opts).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Aduce bin/setenv.bat / bin/setenv.sh la zi cu alegerile din program; scrie doar dacă diferă. Un setenv scris de
   * mână e păstrat ca setenv.*.bak.
   *
   * @return 1 = scris, 0 = era deja la zi (sau nimic de pus), -1 = eroare
   */
  private int syncSetenv(Path tomcat, boolean verbose) {
    Path file = setenvFile(tomcat);
    try {
      byte[] data = wantedSetenv(true);
      if (data == null) return 0;
      if (Files.isRegularFile(file)) {
        byte[] old = Files.readAllBytes(file);
        if (Arrays.equals(old, data)) {
          if (verbose) log("[SETENV] bin/" + file.getFileName() + " era deja la zi.");
          return 0;
        }
        if (!new String(old, StandardCharsets.ISO_8859_1).contains(SETENV_MARK)) {
          Path bak = freeBackup(file);
          Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
          log("[SETENV] Am găsit un " + file.getFileName() + " scris de mână; l-am păstrat ca " + bak.getFileName() + ".");
        }
      }
      Files.write(file, data);
      Path agent = activeAgent(false);
      log("[SETENV] Scris bin/" + file.getFileName() + ": JAVA_HOME=" + jdkHome
          + (agent != null ? ", HotswapAgent=" + agent.getFileName() : ", fără HotswapAgent") + ".");
      return 1;
    } catch (IOException e) {
      log("[EROARE] Nu pot scrie " + file + ": " + e.getMessage());
      return -1;
    }
  }

  /** nume.bak, sau nume.bak2, nume.bak3... dacă există deja (nu suprascriem niciodată o copie de siguranță). */
  static Path freeBackup(Path file) {
    Path bak = file.resolveSibling(file.getFileName() + ".bak");
    for (int i = 2; Files.exists(bak) && i < 100; i++) bak = file.resolveSibling(file.getFileName() + ".bak" + i);
    return bak;
  }

  /** Intervalele [început, sfârșit) ocupate de comentarii XML (ca să nu atingem exemplele comentate din fișier). */
  static List<int[]> xmlComments(String s) {
    List<int[]> out = new ArrayList<>();
    int i = 0;
    while ((i = s.indexOf("<!--", i)) >= 0) {
      int e = s.indexOf("-->", i + 4);
      if (e < 0) {
        out.add(new int[] {i, s.length()});
        break;
      }
      out.add(new int[] {i, e + 3});
      i = e + 3;
    }
    return out;
  }

  static boolean inRanges(List<int[]> ranges, int pos) {
    for (int[] r : ranges) {
      if (pos < r[0]) return false;
      if (pos < r[1]) return true;
    }
    return false;
  }

  /** Prima potrivire din [from, to) care nu e într-un comentariu; null dacă nu există. */
  static Matcher findLive(Pattern p, String s, int from, int to, List<int[]> comments) {
    Matcher m = p.matcher(s);
    m.region(from, to);
    while (m.find()) if (!inRanges(comments, m.start())) return m;
    return null;
  }

  /** Spațiile de la începutul liniei pe care se află {@code pos} ("" dacă înaintea lui pe linie mai e și altceva). */
  static String indentAt(String s, int pos) {
    String pre = s.substring(s.lastIndexOf('\n', pos - 1) + 1, pos);
    return pre.isBlank() ? pre : "";
  }

  static final Pattern X_SERVLET_OPEN = Pattern.compile("<servlet(?:\\s[^>]*)?>");
  static final Pattern X_SERVLET_CLOSE = Pattern.compile("</servlet\\s*>");
  static final Pattern X_JSP_NAME = Pattern.compile("<servlet-name>\\s*jsp\\s*</servlet-name>");
  static final Pattern X_INIT_PARAM = Pattern.compile("<init-param>(.*?)</init-param>", Pattern.DOTALL);
  static final Pattern X_PARAM_NAME = Pattern.compile("<param-name>\\s*([^<]*?)\\s*</param-name>");
  static final Pattern X_PARAM_VALUE = Pattern.compile("<param-value>([^<]*)</param-value>");
  static final Pattern X_LOAD = Pattern.compile("<load-on-startup>");
  static final Pattern X_RESOURCES = Pattern.compile("<Resources(?=[\\s/>])([^>]*)>");
  static final Pattern X_CACHING = Pattern.compile("\\bcachingAllowed\\s*=\\s*(\"[^\"]*\"|'[^']*')");
  static final Pattern X_CONTEXT_CLOSE = Pattern.compile("</Context\\s*>");
  static final Pattern X_CONTEXT_EMPTY = Pattern.compile("<Context((?:\\s[^>]*?)?)\\s*/>");
  static final Pattern X_CHILD_LINE = Pattern.compile("(?m)^([ \\t]+)<[A-Za-z]");

  /**
   * conf/web.xml: la servlet-ul "jsp" pune modificationTestInterval=0 - un JSP modificat e verificat la fiecare cerere,
   * nu o dată la 4 secunde (și development=true, fără de care setarea e ignorată). Editare pe text, minimă: comentariile
   * și alinierea restului fișierului rămân exact cum erau.
   *
   * @return textul nou, sau null dacă setarea e deja pusă
   */
  static String patchWebXml(String xml) throws IOException {
    List<int[]> comments = xmlComments(xml);
    int from = 0;
    while (true) {
      Matcher open = findLive(X_SERVLET_OPEN, xml, from, xml.length(), comments);
      if (open == null) throw new IOException("nu găsesc servlet-ul \"jsp\" (org.apache.jasper.servlet.JspServlet)");
      Matcher close = findLive(X_SERVLET_CLOSE, xml, open.end(), xml.length(), comments);
      if (close == null) throw new IOException("<servlet> fără </servlet>");
      int bs = open.end();
      int be = close.start();
      if (findLive(X_JSP_NAME, xml, bs, be, comments) == null) {
        from = close.end();
        continue;
      }
      String nl = xml.contains("\r\n") ? "\r\n" : "\n";
      List<Object[]> edits = new ArrayList<>(); // {început, sfârșit, text nou}
      boolean hasInterval = false;
      String paramIndent = null;
      String innerIndent = null;
      Matcher ip = X_INIT_PARAM.matcher(xml);
      ip.region(bs, be);
      while (ip.find()) {
        if (inRanges(comments, ip.start())) continue;
        if (paramIndent == null && !indentAt(xml, ip.start()).isEmpty()) paramIndent = indentAt(xml, ip.start());
        Matcher pn = X_PARAM_NAME.matcher(xml);
        pn.region(ip.start(1), ip.end(1));
        Matcher pv = X_PARAM_VALUE.matcher(xml);
        pv.region(ip.start(1), ip.end(1));
        if (!pn.find() || !pv.find()) continue;
        if (innerIndent == null && !indentAt(xml, pn.start()).isEmpty()) innerIndent = indentAt(xml, pn.start());
        String name = pn.group(1);
        String val = pv.group(1).trim();
        if (name.equals("modificationTestInterval")) {
          hasInterval = true;
          if (!val.equals("0")) edits.add(new Object[] {pv.start(1), pv.end(1), "0"});
        } else if (name.equals("development") && val.equalsIgnoreCase("false")) {
          edits.add(new Object[] {pv.start(1), pv.end(1), "true"});
        }
      }
      if (!hasInterval) {
        Matcher los = findLive(X_LOAD, xml, bs, be, comments);
        int anchor = los != null ? los.start() : be;
        int lineStart = xml.lastIndexOf('\n', anchor - 1) + 1;
        boolean alone = xml.substring(lineStart, anchor).isBlank();
        String anchorIndent = alone ? xml.substring(lineStart, anchor) : "";
        Matcher sn = findLive(X_JSP_NAME, xml, bs, be, comments); // un "frate": aceeași indentare ca <servlet-name>
        String sibling = sn == null ? "" : indentAt(xml, sn.start());
        String pi =
            paramIndent != null ? paramIndent
                : los != null && alone ? anchorIndent
                : !sibling.isEmpty() ? sibling
                : anchorIndent + "    ";
        String ii = innerIndent != null ? innerIndent : pi + (pi.endsWith("\t") ? "\t" : "    ");
        String block =
            pi + "<init-param>" + nl
                + ii + "<param-name>modificationTestInterval</param-name>" + nl
                + ii + "<param-value>0</param-value>" + nl
                + pi + "</init-param>" + nl;
        if (alone) edits.add(new Object[] {lineStart, lineStart, block});
        else edits.add(new Object[] {anchor, anchor, nl + block});
      }
      if (edits.isEmpty()) return null;
      edits.sort((x, y) -> Integer.compare((Integer) y[0], (Integer) x[0])); // de la coadă, ca pozițiile să rămână valabile
      StringBuilder sb = new StringBuilder(xml);
      for (Object[] e : edits) sb.replace((Integer) e[0], (Integer) e[1], (String) e[2]);
      return sb.toString();
    }
  }

  /**
   * conf/context.xml: pune &lt;Resources cachingAllowed="false"/&gt; în &lt;Context&gt; - Tomcat nu mai servește din cache
   * (până la 5 secunde) varianta veche a unui fișier modificat. Restul liniilor rămân cum sunt.
   *
   * @return textul nou, sau null dacă setarea e deja pusă
   */
  static String patchContextXml(String xml) throws IOException {
    List<int[]> comments = xmlComments(xml);
    Matcher res = findLive(X_RESOURCES, xml, 0, xml.length(), comments);
    if (res != null) { // există deja un <Resources ...>: îi punem / corectăm doar atributul
      Matcher ca = X_CACHING.matcher(xml);
      ca.region(res.start(1), res.end(1));
      if (ca.find()) {
        String v = ca.group(1);
        if (v.substring(1, v.length() - 1).trim().equalsIgnoreCase("false")) return null;
        return xml.substring(0, ca.start(1)) + "\"false\"" + xml.substring(ca.end(1));
      }
      return xml.substring(0, res.start(1)) + " cachingAllowed=\"false\"" + xml.substring(res.start(1));
    }
    String nl = xml.contains("\r\n") ? "\r\n" : "\n";
    Matcher close = findLive(X_CONTEXT_CLOSE, xml, 0, xml.length(), comments);
    if (close == null) {
      Matcher empty = findLive(X_CONTEXT_EMPTY, xml, 0, xml.length(), comments);
      if (empty == null) throw new IOException("nu găsesc elementul <Context>");
      return xml.substring(0, empty.start()) + "<Context" + empty.group(1) + ">" + nl
          + "    <Resources cachingAllowed=\"false\"/>" + nl + "</Context>" + xml.substring(empty.end());
    }
    int anchor = close.start();
    int lineStart = xml.lastIndexOf('\n', anchor - 1) + 1;
    boolean alone = xml.substring(lineStart, anchor).isBlank();
    String childIndent = null; // indentarea copiilor direcți: cea mai mică dintre liniile cu elemente
    Matcher ch = X_CHILD_LINE.matcher(xml);
    ch.region(0, anchor);
    while (ch.find()) {
      if (inRanges(comments, ch.start(1))) continue;
      if (childIndent == null || ch.group(1).length() < childIndent.length()) childIndent = ch.group(1);
    }
    if (childIndent == null) childIndent = (alone ? xml.substring(lineStart, anchor) : "") + "    ";
    String line = childIndent + "<Resources cachingAllowed=\"false\"/>" + nl;
    if (alone) return xml.substring(0, lineStart) + line + xml.substring(lineStart);
    return xml.substring(0, anchor) + nl + line + xml.substring(anchor);
  }

  interface XmlPatcher {
    /** @return textul modificat, sau null dacă nu e nimic de schimbat */
    String apply(String xml) throws IOException;
  }

  static final Pattern XML_ENCODING = Pattern.compile("<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']");

  static Charset xmlCharset(byte[] b) {
    Matcher m = XML_ENCODING.matcher(new String(b, 0, Math.min(b.length, 200), StandardCharsets.ISO_8859_1));
    if (m.find()) {
      try {
        return Charset.forName(m.group(1));
      } catch (RuntimeException ignored) {
        // encoding necunoscut: UTF-8
      }
    }
    return StandardCharsets.UTF_8;
  }

  /** Textul unui fișier XML și encoding-ul cu care îl putem rescrie fără să pierdem niciun octet. */
  static Object[] readXmlText(Path file) throws IOException {
    byte[] orig = Files.readAllBytes(file);
    Charset cs = xmlCharset(orig);
    String text = new String(orig, cs);
    if (!Arrays.equals(text.getBytes(cs), orig)) { // nu e chiar în encoding-ul declarat: tratăm octeții unu la unu
      cs = StandardCharsets.ISO_8859_1;
      text = new String(orig, cs);
    }
    return new Object[] {text, cs};
  }

  static Document parseXmlText(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setValidating(false);
    f.setXIncludeAware(false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
    b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // erorile vin ca excepții, nu ca text pe consolă
    String s = xml.startsWith("\uFEFF") ? xml.substring(1) : xml;
    return b.parse(new org.xml.sax.InputSource(new java.io.StringReader(s)));
  }

  /**
   * Aplică o modificare pe un fișier din conf/: copie de siguranță (.bak), verificare că rezultatul e XML valid, scriere.
   *
   * @return 1 = modificat, 0 = era deja în regulă, -1 = eroare (fișierul rămâne neschimbat)
   */
  private int applyXmlPatch(Path file, XmlPatcher patcher, String done, String already) {
    try {
      if (!Files.isRegularFile(file)) {
        log("[EROARE] Nu găsesc " + file);
        return -1;
      }
      Object[] read = readXmlText(file);
      String patched = patcher.apply((String) read[0]);
      if (patched == null) {
        log("[CONFIG] " + already);
        return 0;
      }
      parseXmlText(patched); // dacă rezultatul n-ar fi XML valid, nu scriem nimic
      Path bak = freeBackup(file);
      Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
      Files.write(file, patched.getBytes((Charset) read[1]));
      log("[CONFIG] " + done + " Originalul: conf/" + bak.getFileName());
      return 1;
    } catch (Exception e) {
      log("[EROARE] conf/" + file.getFileName() + ": " + e.getMessage() + " - fișierul a rămas neschimbat.");
      return -1;
    }
  }

  /** true dacă Tomcat-ul are deja ambele setări de reload rapid (nu mai e nimic de modificat). */
  static boolean quickReloadApplied(Path tomcat) {
    try {
      Path conf = tomcat.resolve("conf");
      return patchWebXml((String) readXmlText(conf.resolve("web.xml"))[0]) == null
          && patchContextXml((String) readXmlText(conf.resolve("context.xml"))[0]) == null;
    } catch (Exception e) {
      return false;
    }
  }

  /** Textul de lângă butonul de configurare: ce e deja pus în Tomcat-ul ales și ce lipsește. Din orice thread. */
  private void refreshConfigState(Path tomcat) {
    String text;
    Color color;
    try {
      boolean quick = quickReloadApplied(tomcat);
      byte[] want = wantedSetenv(false);
      Path f = setenvFile(tomcat);
      boolean envOk = want == null || (Files.isRegularFile(f) && Arrays.equals(Files.readAllBytes(f), want));
      if (quick && envOk) {
        text = "\u2713 setenv la zi, reload rapid JSP activ";
        color = okColor();
      } else {
        text = (envOk ? "setenv la zi" : "setenv: nescris") + "  |  reload rapid JSP: " + (quick ? "activ" : "neaplicat")
            + "  \u2190 apasă butonul";
        color = warnColor();
      }
    } catch (Exception e) {
      text = " ";
      color = mutedColor();
    }
    final String t = text;
    final Color c = color;
    SwingUtilities.invokeLater(
        () -> {
          if (!tomcat.equals(tomcatHome)) return;
          lblConfig.setText(t);
          lblConfig.setForeground(c);
        });
  }

  /**
   * Butonul "Configurează Tomcat": face tot ce se făcea de mână - scrie bin/setenv din alegerile din program și pune în
   * conf/web.xml + conf/context.xml setările cu care un JSP salvat se vede la primul refresh din browser.
   */
  private void configureTomcat() {
    final Path home = tomcatHome;
    if (home == null) return;
    jvmOptsEdited();
    btnConfigure.setEnabled(false);
    bg.submit(
        () -> {
          try {
            log("[CONFIG] Configurez " + home + " ...");
            Path conf = home.resolve("conf");
            int a = syncSetenv(home, true);
            int b =
                applyXmlPatch(
                    conf.resolve("web.xml"),
                    TomcatDeploy::patchWebXml,
                    "web.xml: servlet-ul jsp are acum modificationTestInterval=0 (JSP-ul salvat se vede la primul refresh).",
                    "web.xml: modificationTestInterval=0 era deja pus.");
            int c =
                applyXmlPatch(
                    conf.resolve("context.xml"),
                    TomcatDeploy::patchContextXml,
                    "context.xml: am adăugat <Resources cachingAllowed=\"false\"/> (fără cache pentru fișierele modificate).",
                    "context.xml: cachingAllowed=\"false\" era deja pus.");
            refreshConfigState(home);
            if (a < 0 || b < 0 || c < 0) return;
            if (a + b + c == 0) {
              log("[OK] Tomcat era deja configurat - nu am modificat nimic.");
            } else if (!portOpen(httpPort(home))) {
              log("[OK] Tomcat configurat. Setările se aplică la următoarea pornire.");
            } else if (askRestart()) {
              if (stopTomcatNow(home)) startTomcatNow(home);
            } else {
              log("[OK] Tomcat configurat. Repornește-l ca setările să aibă efect.");
            }
          } finally {
            SwingUtilities.invokeLater(() -> btnConfigure.setEnabled(tomcatHome != null));
          }
        });
  }

  /** Întreabă (pe EDT) dacă repornim Tomcat-ul aflat în execuție. Apelat dintr-un thread de fundal. */
  private boolean askRestart() {
    final boolean[] ok = {false};
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            showFromTray();
            ok[0] =
                JOptionPane.showConfirmDialog(
                        this,
                        "Tomcat rulează acum, iar setările noi au efect abia după repornire.\n\nÎl repornesc acum?",
                        "Repornire Tomcat",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE)
                    == JOptionPane.YES_OPTION;
          });
    } catch (Exception e) {
      return false;
    }
    return ok[0];
  }

  // ===================================================================================
  // Tray (zona de notificare de lângă ceas): fereastra se ascunde acolo la minimizare, fără programe gen RBTray
  // ===================================================================================
  private java.awt.TrayIcon trayIcon;
  private boolean trayAdded;
  private volatile boolean lastCompileFailed;
  private long lastBalloon;
  private final Map<Integer, java.awt.Image> iconCache = new HashMap<>();

  /** Iconița programului, desenată în cod (fără fișiere): verde = Tomcat pornit, gri = oprit, roșu = eroare de compilare. */
  static java.awt.Image makeIcon(int size, boolean up, boolean deploy, boolean error) {
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D g = img.createGraphics();
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    Color base = error ? new Color(0xC62828) : up ? new Color(0x2E7D32) : new Color(0x616161);
    g.setColor(base);
    g.fillRoundRect(1, 1, size - 2, size - 2, size / 3, size / 3);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size * 0.74f)));
    java.awt.FontMetrics fm = g.getFontMetrics();
    g.drawString("T", (size - fm.stringWidth("T")) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
    if (deploy) { // punct galben: deploy-ul live e activ
      int d = Math.round(size * 0.40f);
      g.setColor(base.darker());
      g.fillOval(size - d - 1, size - d - 1, d + 1, d + 1);
      g.setColor(new Color(0xFFC107));
      g.fillOval(size - d + 1, size - d + 1, d - 3, d - 3);
    }
    g.dispose();
    return img;
  }

  private static java.awt.MenuItem menuItem(String label, Runnable action) {
    java.awt.MenuItem mi = new java.awt.MenuItem(label);
    mi.addActionListener(e -> action.run());
    return mi;
  }

  /** Pregătește iconița din tray. Pe Linux prin D-Bus (desktopurile moderne), altfel prin tray-ul clasic. Doar pe EDT. */
  private void initTray() {
    updateTray(); // pune și iconița ferestrei
    chkTray.setSelected(prefs.getBoolean(PREF_TRAY, true));
    chkTray.addActionListener(
        e -> {
          prefs.putBoolean(PREF_TRAY, chkTray.isSelected());
          flushPrefs();
          if (linuxTray != null) linuxTray.failed = false; // la bifare mai încercăm o dată
          syncTrayIcon();
        });
    boolean supported;
    try {
      supported = java.awt.SystemTray.isSupported();
    } catch (Throwable t) {
      supported = false;
    }
    if (supported) {
      try {
        java.awt.PopupMenu menu = new java.awt.PopupMenu();
        menu.add(menuItem("Arată fereastra", this::showFromTray));
        menu.addSeparator();
        menu.add(menuItem("Pornește Tomcat", this::startTomcat));
        menu.add(menuItem("Oprește Tomcat", this::stopTomcat));
        menu.add(menuItem("Pornește / oprește Deploy Live", () -> { if (btnDeploy.isEnabled()) toggleDeploy(); }));
        menu.add(menuItem("Deschide în browser", this::openInBrowser));
        menu.addSeparator();
        menu.add(menuItem("Ieșire", this::exitApp));
        trayIcon = new java.awt.TrayIcon(iconFor(), "TomcatDeploy", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showFromTray()); // dublu-click
        trayIcon.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) showFromTray(); // un click simplu ajunge
              }
            });
      } catch (Throwable t) {
        trayIcon = null;
      }
    }
    // GNOME / Zorin / Ubuntu / KDE / Cinnamon nu mai au tray-ul clasic pe care îl știe Java: acolo iconița se pune prin D-Bus
    if (!WINDOWS && !MAC && !Boolean.getBoolean("td.nodbus")) linuxTray = new LinuxTray();
    syncTrayIcon();
  }

  private boolean trayReady() {
    LinuxTray lt = linuxTray;
    return trayAdded || (lt != null && lt.active);
  }

  /** Iconița stă în tray cât timp opțiunea e bifată; debifată, dispare (și fereastra se minimizează normal). Doar pe EDT. */
  private void syncTrayIcon() {
    boolean want = chkTray.isSelected();
    final LinuxTray lt = linuxTray;
    if (lt != null && want && !lt.active && !lt.failed) {
      trayExec.submit(
          () -> { // conectarea la D-Bus se face în fundal, nu ține interfața pe loc
            boolean ok = lt.start();
            SwingUtilities.invokeLater(
                () -> {
                  if (!ok) lt.failed = true;
                  syncTrayIcon();
                });
          });
      return;
    }
    if (lt != null && !want && lt.active) lt.stop();
    boolean awtWanted = want && (lt == null || !lt.active);
    if (trayIcon != null) {
      try {
        if (awtWanted && !trayAdded) {
          java.awt.SystemTray.getSystemTray().add(trayIcon);
          trayAdded = true;
        } else if (!awtWanted && trayAdded) {
          java.awt.SystemTray.getSystemTray().remove(trayIcon);
          trayAdded = false;
        }
      } catch (Exception | LinkageError e) { // desktopul nu are tray, deși a spus că are
        trayAdded = false;
        trayIcon = null;
      }
    }
    if (want && !trayReady()) { // nicio variantă nu merge pe acest desktop
      chkTray.setSelected(false);
      chkTray.setEnabled(false);
      chkTray.setText("Tray indisponibil pe acest desktop (fereastra se minimizează normal)");
    }
    if (!trayReady() && hiddenInTray) showFromTray(); // nu lăsăm niciodată fereastra ascunsă fără iconiță
    updateTray();
  }

  private void removeTrayIcon() {
    LinuxTray lt = linuxTray;
    if (lt != null) lt.stop();
    if (trayIcon == null || !trayAdded) return;
    try {
      java.awt.SystemTray.getSystemTray().remove(trayIcon);
    } catch (Exception | LinkageError ignored) {
      // oricum ieșim
    }
    trayAdded = false;
  }

  private void hideToTray() {
    hiddenInTray = true;
    stateBeforeTray = getExtendedState() & ~ICONIFIED; // ținem noi minte dacă era maximizată: la ascundere Java îi cere
    // managerului de ferestre să o de-maximizeze, iar starea se poate pierde
    setVisible(false); // dispare și din bara de activități; programul lucrează mai departe
    if (!prefs.getBoolean(PREF_TRAY_HINT, false)) {
      prefs.putBoolean(PREF_TRAY_HINT, true);
      flushPrefs();
      balloon("TomcatDeploy lucrează în continuare",
          "Fereastra e ascunsă lângă ceas. Click pe iconița T (sau „Arată fereastra” din meniul ei) ca s-o redeschizi.",
          java.awt.TrayIcon.MessageType.INFO);
    }
  }

  /** Cât timp (după o readucere din tray) o minimizare venită de la managerul de ferestre nu mai ascunde fereastra. */
  private long ignoreIconifyUntil;
  /** Starea ferestrei (normală / maximizată) din clipa în care a fost ascunsă în tray. */
  private int stateBeforeTray;

  /** Readuce fereastra din tray (sau din starea minimizată) în față. Doar pe EDT. */
  private void showFromTray() {
    hiddenInTray = false;
    ignoreIconifyUntil = System.currentTimeMillis() + 2500;
    if (!isVisible()) {
      if (!WINDOWS && !MAC && isDisplayable()) {
        // Linux: fereastra a fost ascunsă cât era minimizată, iar Java ține minte starea "ascunsă" primită atunci de la
        // managerul de ferestre și i-o dă înapoi la reafișare - GNOME o minimizează pe loc (și ar ajunge iar în tray).
        // O fereastră nativă nouă nu are starea aceea: apare normal, în față, cu tot conținutul ei.
        dispose();
      }
      setExtendedState(stateBeforeTray & ~ICONIFIED);
      setVisible(true);
    } else {
      setExtendedState(getExtendedState() & ~ICONIFIED); // era doar minimizată: restaurare obișnuită
    }
    toFront();
    requestFocus();
    // Verificare: dacă managerul de ferestre a lăsat-o totuși minimizată, mai cerem o dată restaurarea.
    javax.swing.Timer check =
        new javax.swing.Timer(
            700,
            e -> {
              if (isVisible() && (getExtendedState() & ICONIFIED) != 0) {
                ignoreIconifyUntil = System.currentTimeMillis() + 2500;
                setExtendedState(getExtendedState() & ~ICONIFIED);
                toFront();
              }
            });
    check.setRepeats(false);
    check.start();
  }

  private void exitApp() {
    savePrefs();
    removeTrayIcon();
    System.exit(0);
  }

  private java.awt.Image iconFor() {
    boolean up = tomcatUp;
    boolean dep = deployRunning;
    boolean err = lastCompileFailed;
    return iconCache.computeIfAbsent((up ? 1 : 0) | (dep ? 2 : 0) | (err ? 4 : 0), k -> makeIcon(64, up, dep, err));
  }

  private String trayText() {
    return "Tomcat " + (tomcatUp ? "pornit" : "oprit") + (deployRunning ? ", deploy live activ" : "")
        + (lastCompileFailed ? ", EROARE de compilare" : "");
  }

  /** Aduce iconița (fereastră + tray) și textul ei la starea curentă. Din orice thread. */
  private void updateTray() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::updateTray);
      return;
    }
    java.awt.Image img = iconFor();
    setIconImage(img);
    LinuxTray lt = linuxTray;
    if (lt != null) lt.refresh();
    if (trayIcon == null) return;
    trayIcon.setImage(img);
    trayIcon.setToolTip("TomcatDeploy - " + trayText());
  }

  /** Notificare lângă ceas (cel mult una la 4 secunde). Din orice thread. */
  private void balloon(String title, String text, java.awt.TrayIcon.MessageType type) {
    SwingUtilities.invokeLater(
        () -> {
          if (!trayReady()) return;
          long now = System.currentTimeMillis();
          if (now - lastBalloon < 4000) return;
          lastBalloon = now;
          final LinuxTray lt = linuxTray;
          if (lt != null && lt.active) {
            trayExec.submit(() -> lt.notify(title, text, type == java.awt.TrayIcon.MessageType.ERROR));
            return;
          }
          try {
            if (trayIcon != null && trayAdded) trayIcon.displayMessage(title, text, type);
          } catch (RuntimeException ignored) {
            // doar informativ
          }
        });
  }

  // -----------------------------------------------------------------------------------
  // Linux: iconița din tray prin D-Bus (StatusNotifierItem) și notificările desktopului
  // -----------------------------------------------------------------------------------
  private LinuxTray linuxTray;
  private boolean hiddenInTray;
  /** Fir propriu pentru discuția cu D-Bus: iconița și notificările nu așteaptă după un build aflat pe firul de fundal. */
  private final ExecutorService trayExec = Executors.newSingleThreadExecutor(daemon("tray"));

  /**
   * Iconița din tray pe Linux. Desktopurile moderne (GNOME cu AppIndicator, Zorin, Ubuntu, KDE, Cinnamon, XFCE) nu mai
   * au tray-ul clasic (XEmbed), singurul pe care îl știe Java, ci protocolul StatusNotifierItem peste D-Bus: programul
   * publică un obiect cu iconița, textul și meniul lui (DBusMenu), iar panoul le afișează.
   */
  private final class LinuxTray {
    static final String ITEM = "/StatusNotifierItem";
    static final String MENU = "/MenuBar";
    static final String SNI = "org.kde.StatusNotifierItem";
    static final String DBM = "com.canonical.dbusmenu";
    static final String WATCHER = "org.kde.StatusNotifierWatcher";
    static final String PROPS = "org.freedesktop.DBus.Properties";
    static final String UNKNOWN = "org.freedesktop.DBus.Error.UnknownMethod";

    /** Meniul iconiței: indexul e id-ul din DBusMenu (0 = rădăcina, "-" = separator). */
    private final String[] labels = {
      "", "Arată fereastra", "-", "Pornește Tomcat", "Oprește Tomcat", "Pornește / oprește Deploy Live", "Deschide în browser", "-", "Ieșire"
    };
    private final String[] itemProps = {
      "Category", "Id", "Title", "Status", "WindowId", "IconName", "IconPixmap", "OverlayIconName", "OverlayIconPixmap",
      "AttentionIconName", "AttentionIconPixmap", "AttentionMovieName", "ToolTip", "ItemIsMenu", "Menu", "IconThemePath"
    };
    private final String[] menuProps = {"Version", "TextDirection", "Status", "IconThemePath"};
    private final Map<Integer, byte[]> pixmapCache = new ConcurrentHashMap<>();
    private volatile DBus bus;
    volatile boolean active;
    volatile boolean failed;
    private volatile String busName;
    private volatile int lastState = -1;

    /** Se conectează la sesiunea D-Bus și înregistrează iconița. @return true dacă desktopul o afișează */
    synchronized boolean start() {
      if (active) return true;
      try {
        DBus b = DBus.connect();
        bus = b;
        b.onMessage = this::handle;
        b.onClosed = this::lost;
        busName = "org.kde.StatusNotifierItem-" + ProcessHandle.current().pid() + "-1";
        b.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su",
            new DBus.Out().str(busName).u32(4), 3000);
        b.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s",
            new DBus.Out().str("type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',"
                + "member='NameOwnerChanged',arg0='" + WATCHER + "'"), 3000);
        register(b);
        lastState = stateBits();
        active = true;
        return true;
      } catch (IOException | RuntimeException | LinkageError e) {
        stop();
        return false;
      }
    }

    private void register(DBus b) throws IOException {
      b.call(WATCHER, "/StatusNotifierWatcher", WATCHER, "RegisterStatusNotifierItem", "s", new DBus.Out().str(busName), 3000);
      boolean host = true; // dacă proprietatea nu poate fi citită, presupunem că panoul există
      try {
        DBus.Msg r = b.call(WATCHER, "/StatusNotifierWatcher", PROPS, "Get", "ss",
            new DBus.Out().str(WATCHER).str("IsStatusNotifierHostRegistered"), 3000);
        if ("b".equals(r.body.sig())) host = r.body.u32() != 0;
      } catch (IOException | RuntimeException e) {
        // rămâne true
      }
      if (!host) throw new IOException("niciun panou nu afișează iconițe"); // altfel fereastra s-ar ascunde "nicăieri"
    }

    synchronized void stop() {
      active = false;
      DBus b = bus;
      bus = null;
      if (b != null) {
        b.onClosed = null;
        b.close();
      }
    }

    /** Conexiunea s-a închis fără voia noastră (sesiunea D-Bus a dispărut): fereastra nu rămâne ascunsă. */
    private void lost() {
      active = false;
      bus = null;
      SwingUtilities.invokeLater(TomcatDeploy.this::syncTrayIcon);
    }

    private int stateBits() {
      return (tomcatUp ? 1 : 0) | (deployRunning ? 2 : 0) | (lastCompileFailed ? 4 : 0);
    }

    /** Starea s-a schimbat: anunțăm panoul să recitească iconița și textul. */
    void refresh() {
      final DBus b = bus;
      int st = stateBits();
      if (b == null || !active || st == lastState) return;
      lastState = st;
      trayExec.submit(
          () -> {
            try {
              b.signal(ITEM, SNI, "NewIcon", "", null);
              b.signal(ITEM, SNI, "NewToolTip", "", null);
            } catch (IOException ignored) {
              // conexiune închisă între timp
            }
          });
    }

    /** Notificare de desktop (org.freedesktop.Notifications). */
    void notify(String title, String text, boolean error) {
      DBus b = bus;
      if (b == null) return;
      try {
        DBus.Out o = new DBus.Out();
        o.str("TomcatDeploy").u32(0).str(error ? "dialog-error" : "dialog-information").str(title);
        o.str(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")); // corpul poate fi interpretat ca marcaj
        o.end(o.array(4)); // fără butoane
        int[] hints = o.array(8);
        o.struct().str("urgency").sig("y").u8(error ? 2 : 1);
        o.end(hints);
        o.u32(error ? 10000 : 5000);
        b.send(1, 1, "/org/freedesktop/Notifications", "org.freedesktop.Notifications", "Notify", null, 0,
            "org.freedesktop.Notifications", "susssasa{sv}i", o);
      } catch (IOException | RuntimeException ignored) {
        // doar informativ
      }
    }

    // ---- cereri primite de la panou (pe thread-ul de citire: răspundem repede, acțiunile merg pe EDT) ----
    private void handle(DBus.Msg m) {
      final DBus b = bus;
      if (b == null) return;
      try {
        if (m.type == 4) {
          if ("NameOwnerChanged".equals(m.member) && "sss".equals(m.sig)) {
            String name = m.body.str();
            m.body.str();
            String owner = m.body.str();
            if (!WATCHER.equals(name)) return;
            if (owner.isEmpty()) { // panoul a dispărut (repornire a desktopului): arătăm fereastra dacă era ascunsă
              SwingUtilities.invokeLater(() -> { if (hiddenInTray) showFromTray(); });
            } else {
              trayExec.submit(
                  () -> {
                    try {
                      register(b);
                    } catch (IOException | RuntimeException e) {
                      // rămânem conectați; încercăm din nou la următoarea apariție
                    }
                  });
            }
          }
          return;
        }
        if (m.type != 1) return;
        String member = m.member == null ? "" : m.member;
        switch (m.iface == null ? "" : m.iface) {
          case PROPS -> properties(b, m, member);
          case SNI -> {
            switch (member) {
              case "Activate", "SecondaryActivate" -> {
                SwingUtilities.invokeLater(TomcatDeploy.this::showFromTray);
                b.reply(m, "", null);
              }
              case "ContextMenu", "Scroll", "ProvideXdgActivationToken" -> b.reply(m, "", null);
              default -> b.error(m, UNKNOWN, member);
            }
          }
          case DBM -> menu(b, m, member);
          case "org.freedesktop.DBus.Introspectable" -> b.reply(m, "s", new DBus.Out().str(introspect(m.path)));
          case "org.freedesktop.DBus.Peer" -> {
            if ("Ping".equals(member)) b.reply(m, "", null);
            else b.error(m, UNKNOWN, member);
          }
          default -> b.error(m, UNKNOWN, member);
        }
      } catch (IOException | RuntimeException e) {
        // cerere pe care n-o înțelegem sau conexiune închisă: o ignorăm
      }
    }

    private void properties(DBus b, DBus.Msg m, String member) throws IOException {
      if ("GetAll".equals(member)) {
        String iface = m.body.str();
        if (iface.isEmpty()) iface = MENU.equals(m.path) ? DBM : SNI;
        DBus.Out o = new DBus.Out();
        int[] a = o.array(8);
        if (SNI.equals(iface)) {
          for (String p : itemProps) {
            o.struct().str(p);
            itemProp(o, p);
          }
        } else if (DBM.equals(iface)) {
          for (String p : menuProps) {
            o.struct().str(p);
            menuProp(o, p);
          }
        }
        o.end(a);
        b.reply(m, "a{sv}", o);
      } else if ("Get".equals(member)) {
        String iface = m.body.str();
        if (iface.isEmpty()) iface = MENU.equals(m.path) ? DBM : SNI;
        String prop = m.body.str();
        DBus.Out o = new DBus.Out();
        boolean ok = SNI.equals(iface) ? itemProp(o, prop) : DBM.equals(iface) && menuProp(o, prop);
        if (ok) b.reply(m, "v", o);
        else b.error(m, "org.freedesktop.DBus.Error.UnknownProperty", prop);
      } else {
        b.error(m, "org.freedesktop.DBus.Error.PropertyReadOnly", member);
      }
    }

    /** Scrie valoarea (ca variantă) a unei proprietăți a iconiței. @return false dacă proprietatea nu există */
    private boolean itemProp(DBus.Out o, String p) {
      switch (p) {
        case "Category" -> o.vStr("ApplicationStatus");
        case "Id", "Title" -> o.vStr("TomcatDeploy");
        case "Status" -> o.vStr("Active");
        case "WindowId" -> o.sig("i").u32(0);
        case "IconName", "OverlayIconName", "AttentionIconName", "AttentionMovieName", "IconThemePath" -> o.vStr("");
        case "IconPixmap" -> {
          o.sig("a(iiay)");
          pixmaps(o, true);
        }
        case "OverlayIconPixmap", "AttentionIconPixmap" -> {
          o.sig("a(iiay)");
          pixmaps(o, false);
        }
        case "ToolTip" -> {
          o.sig("(sa(iiay)ss)");
          o.struct().str("");
          pixmaps(o, false);
          o.str("TomcatDeploy").str(trayText());
        }
        case "ItemIsMenu" -> o.vBool(false);
        case "Menu" -> o.sig("o").str(MENU);
        default -> {
          return false;
        }
      }
      return true;
    }

    private boolean menuProp(DBus.Out o, String p) {
      switch (p) {
        case "Version" -> o.sig("u").u32(3);
        case "TextDirection" -> o.vStr("ltr");
        case "Status" -> o.vStr("normal");
        case "IconThemePath" -> {
          o.sig("as");
          o.end(o.array(4));
        }
        default -> {
          return false;
        }
      }
      return true;
    }

    /** Iconița ca listă de imagini (lățime, înălțime, pixeli ARGB cu octetul cel mai semnificativ primul). */
    private void pixmaps(DBus.Out o, boolean real) {
      int[] a = o.array(8);
      if (real) {
        int st = stateBits();
        for (int size : new int[] {24, 48}) {
          byte[] px =
              pixmapCache.computeIfAbsent(
                  st * 1000 + size,
                  k -> {
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = img.createGraphics();
                    g.drawImage(makeIcon(size, (st & 1) != 0, (st & 2) != 0, (st & 4) != 0), 0, 0, null);
                    g.dispose();
                    byte[] out = new byte[size * size * 4];
                    int i = 0;
                    for (int y = 0; y < size; y++) {
                      for (int x = 0; x < size; x++) {
                        int argb = img.getRGB(x, y);
                        out[i++] = (byte) (argb >>> 24);
                        out[i++] = (byte) (argb >>> 16);
                        out[i++] = (byte) (argb >>> 8);
                        out[i++] = (byte) argb;
                      }
                    }
                    return out;
                  });
          o.struct().u32(size).u32(size);
          int[] d = o.array(1);
          o.raw(px);
          o.end(d);
        }
      }
      o.end(a);
    }

    private void menu(DBus b, DBus.Msg m, String member) throws IOException {
      switch (member) {
        case "GetLayout" -> {
          int parent = m.body.u32();
          DBus.Out o = new DBus.Out().u32(1);
          node(o, parent > 0 && parent < labels.length ? parent : 0);
          b.reply(m, "u(ia{sv}av)", o);
        }
        case "GetGroupProperties" -> {
          int len = m.body.u32();
          int end = m.body.p + len;
          List<Integer> ids = new ArrayList<>();
          while (m.body.p < end) ids.add(m.body.u32());
          if (ids.isEmpty()) for (int i = 0; i < labels.length; i++) ids.add(i);
          DBus.Out o = new DBus.Out();
          int[] a = o.array(8);
          for (int id : ids) {
            if (id < 0 || id >= labels.length) continue;
            o.struct().u32(id);
            nodeProps(o, id);
          }
          o.end(a);
          b.reply(m, "a(ia{sv})", o);
        }
        case "GetProperty" -> {
          int id = m.body.u32();
          String name = m.body.str();
          DBus.Out o = new DBus.Out();
          if (id < 0 || id >= labels.length) {
            b.error(m, "org.freedesktop.DBus.Error.InvalidArgs", "id");
          } else if ("label".equals(name)) {
            b.reply(m, "v", o.vStr("-".equals(labels[id]) ? "" : labels[id]));
          } else if ("enabled".equals(name) || "visible".equals(name)) {
            b.reply(m, "v", o.vBool(true));
          } else if ("type".equals(name)) {
            b.reply(m, "v", o.vStr("-".equals(labels[id]) ? "separator" : "standard"));
          } else if ("children-display".equals(name)) {
            b.reply(m, "v", o.vStr(id == 0 ? "submenu" : ""));
          } else {
            b.error(m, "org.freedesktop.DBus.Error.InvalidArgs", name);
          }
        }
        case "Event" -> {
          int id = m.body.u32();
          if ("clicked".equals(m.body.str())) clicked(id);
          b.reply(m, "", null);
        }
        case "EventGroup" -> {
          int len = m.body.u32();
          m.body.align(8);
          int end = m.body.p + len;
          while (m.body.p < end) {
            m.body.align(8);
            int id = m.body.u32();
            String ev = m.body.str();
            m.body.skip("v", 0);
            m.body.u32();
            if ("clicked".equals(ev)) clicked(id);
          }
          DBus.Out o = new DBus.Out();
          o.end(o.array(4));
          b.reply(m, "ai", o);
        }
        case "AboutToShow" -> b.reply(m, "b", new DBus.Out().bool(false));
        case "AboutToShowGroup" -> {
          DBus.Out o = new DBus.Out();
          o.end(o.array(4));
          o.end(o.array(4));
          b.reply(m, "aiai", o);
        }
        default -> b.error(m, UNKNOWN, member);
      }
    }

    /** Un element de meniu ca (id, proprietăți, copii), recursiv - forma cerută de DBusMenu.GetLayout. */
    private void node(DBus.Out o, int id) {
      o.struct().u32(id);
      nodeProps(o, id);
      int[] kids = o.array(1);
      if (id == 0) {
        for (int i = 1; i < labels.length; i++) {
          o.sig("(ia{sv}av)");
          node(o, i);
        }
      }
      o.end(kids);
    }

    private void nodeProps(DBus.Out o, int id) {
      int[] a = o.array(8);
      if (id == 0) {
        o.struct().str("children-display").vStr("submenu");
      } else if ("-".equals(labels[id])) {
        o.struct().str("type").vStr("separator");
      } else {
        o.struct().str("label").vStr(labels[id]);
        o.struct().str("enabled").vBool(true);
        o.struct().str("visible").vBool(true);
      }
      o.end(a);
    }

    private void clicked(int id) {
      SwingUtilities.invokeLater(
          () -> {
            switch (id) {
              case 1 -> showFromTray();
              case 3 -> startTomcat();
              case 4 -> stopTomcat();
              case 5 -> {
                if (btnDeploy.isEnabled()) toggleDeploy();
              }
              case 6 -> openInBrowser();
              case 8 -> exitApp();
              default -> { }
            }
          });
    }

    private String introspect(String path) {
      String head = "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\""
          + " \"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>\n";
      String common = " <interface name=\"org.freedesktop.DBus.Properties\">"
          + "<method name=\"Get\"><arg type=\"s\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/><arg type=\"v\" direction=\"out\"/></method>"
          + "<method name=\"GetAll\"><arg type=\"s\" direction=\"in\"/><arg type=\"a{sv}\" direction=\"out\"/></method></interface>\n"
          + " <interface name=\"org.freedesktop.DBus.Introspectable\"><method name=\"Introspect\"><arg type=\"s\" direction=\"out\"/></method></interface>\n";
      if (ITEM.equals(path)) {
        StringBuilder sb = new StringBuilder(head).append(common).append(" <interface name=\"").append(SNI).append("\">");
        String[] types = {"s", "s", "s", "s", "i", "s", "a(iiay)", "s", "a(iiay)", "s", "a(iiay)", "s", "(sa(iiay)ss)", "b", "o", "s"};
        for (int i = 0; i < itemProps.length; i++) {
          sb.append("<property name=\"").append(itemProps[i]).append("\" type=\"").append(types[i]).append("\" access=\"read\"/>");
        }
        for (String mth : new String[] {"ContextMenu", "Activate", "SecondaryActivate"}) {
          sb.append("<method name=\"").append(mth).append("\"><arg type=\"i\" direction=\"in\"/><arg type=\"i\" direction=\"in\"/></method>");
        }
        sb.append("<method name=\"Scroll\"><arg type=\"i\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/></method>");
        for (String sg : new String[] {"NewTitle", "NewIcon", "NewAttentionIcon", "NewOverlayIcon", "NewToolTip"}) {
          sb.append("<signal name=\"").append(sg).append("\"/>");
        }
        return sb.append("<signal name=\"NewStatus\"><arg type=\"s\"/></signal></interface>\n</node>\n").toString();
      }
      if (MENU.equals(path)) {
        return head + common + " <interface name=\"" + DBM + "\">"
            + "<property name=\"Version\" type=\"u\" access=\"read\"/><property name=\"TextDirection\" type=\"s\" access=\"read\"/>"
            + "<property name=\"Status\" type=\"s\" access=\"read\"/><property name=\"IconThemePath\" type=\"as\" access=\"read\"/>"
            + "<method name=\"GetLayout\"><arg type=\"i\" direction=\"in\"/><arg type=\"i\" direction=\"in\"/><arg type=\"as\" direction=\"in\"/>"
            + "<arg type=\"u\" direction=\"out\"/><arg type=\"(ia{sv}av)\" direction=\"out\"/></method>"
            + "<method name=\"GetGroupProperties\"><arg type=\"ai\" direction=\"in\"/><arg type=\"as\" direction=\"in\"/><arg type=\"a(ia{sv})\" direction=\"out\"/></method>"
            + "<method name=\"GetProperty\"><arg type=\"i\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/><arg type=\"v\" direction=\"out\"/></method>"
            + "<method name=\"Event\"><arg type=\"i\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/><arg type=\"v\" direction=\"in\"/><arg type=\"u\" direction=\"in\"/></method>"
            + "<method name=\"EventGroup\"><arg type=\"a(isvu)\" direction=\"in\"/><arg type=\"ai\" direction=\"out\"/></method>"
            + "<method name=\"AboutToShow\"><arg type=\"i\" direction=\"in\"/><arg type=\"b\" direction=\"out\"/></method>"
            + "<method name=\"AboutToShowGroup\"><arg type=\"ai\" direction=\"in\"/><arg type=\"ai\" direction=\"out\"/><arg type=\"ai\" direction=\"out\"/></method>"
            + "<signal name=\"ItemsPropertiesUpdated\"><arg type=\"a(ia{sv})\"/><arg type=\"a(ias)\"/></signal>"
            + "<signal name=\"LayoutUpdated\"><arg type=\"u\"/><arg type=\"i\"/></signal>"
            + "<signal name=\"ItemActivationRequested\"><arg type=\"i\"/><arg type=\"u\"/></signal></interface>\n</node>\n";
      }
      return head + ("/".equals(path) ? " <node name=\"StatusNotifierItem\"/>\n <node name=\"MenuBar\"/>\n" : "") + "</node>\n";
    }
  }

  /**
   * Client D-Bus minimal, în Java pur (fără librării): conexiune pe socketul sesiunii, autentificare EXTERNAL, mesaje
   * little-endian. Conține doar ce ne trebuie pentru iconița din tray și notificări.
   */
  static final class DBus implements AutoCloseable {
    /** Scriere de valori D-Bus, cu alinierile cerute de protocol. */
    static final class Out {
      byte[] b = new byte[512];
      int n;

      private void need(int k) {
        if (n + k > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, n + k));
      }

      Out align(int a) {
        while (n % a != 0) u8(0);
        return this;
      }

      Out u8(int v) {
        need(1);
        b[n++] = (byte) v;
        return this;
      }

      Out u32(int v) {
        align(4);
        need(4);
        b[n++] = (byte) v;
        b[n++] = (byte) (v >>> 8);
        b[n++] = (byte) (v >>> 16);
        b[n++] = (byte) (v >>> 24);
        return this;
      }

      Out bool(boolean v) {
        return u32(v ? 1 : 0);
      }

      Out raw(byte[] d) {
        need(d.length);
        System.arraycopy(d, 0, b, n, d.length);
        n += d.length;
        return this;
      }

      /** Text (sau cale de obiect): lungime, octeți UTF-8, zero final. */
      Out str(String s) {
        byte[] u = s.replace('\0', ' ').getBytes(StandardCharsets.UTF_8);
        u32(u.length);
        raw(u);
        return u8(0);
      }

      Out sig(String s) {
        byte[] u = s.getBytes(StandardCharsets.US_ASCII);
        u8(u.length);
        raw(u);
        return u8(0);
      }

      /** Începe un tablou cu elemente aliniate la {@code elemAlign}; rezultatul se dă lui {@link #end}. */
      int[] array(int elemAlign) {
        u32(0);
        int lenPos = n - 4;
        align(elemAlign);
        return new int[] {lenPos, n};
      }

      Out end(int[] a) {
        int len = n - a[1];
        b[a[0]] = (byte) len;
        b[a[0] + 1] = (byte) (len >>> 8);
        b[a[0] + 2] = (byte) (len >>> 16);
        b[a[0] + 3] = (byte) (len >>> 24);
        return this;
      }

      /** Început de structură sau de intrare de dicționar (aliniere la 8). */
      Out struct() {
        return align(8);
      }

      Out vStr(String s) {
        return sig("s").str(s);
      }

      Out vBool(boolean v) {
        return sig("b").bool(v);
      }
    }

    /** Citire de valori D-Bus dintr-un mesaj primit. */
    static final class In {
      final byte[] b;
      int p;

      In(byte[] b, int p) {
        this.b = b;
        this.p = p;
      }

      void align(int a) {
        int r = p % a;
        if (r != 0) p += a - r;
      }

      int u8() {
        return b[p++] & 0xff;
      }

      int u32() {
        align(4);
        int v = (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
        p += 4;
        return v;
      }

      String str() {
        int len = u32();
        String s = new String(b, p, len, StandardCharsets.UTF_8);
        p += len + 1;
        return s;
      }

      String sig() {
        int len = u8();
        String s = new String(b, p, len, StandardCharsets.US_ASCII);
        p += len + 1;
        return s;
      }

      static int alignOf(char c) {
        return switch (c) {
          case 'y', 'g', 'v' -> 1;
          case 'n', 'q' -> 2;
          case 'x', 't', 'd', '(', '{' -> 8;
          default -> 4;
        };
      }

      /** Indexul de după tipul complet care începe la {@code sig[i]}. */
      static int typeEnd(String sig, int i) {
        char c = sig.charAt(i);
        if (c == 'a') return typeEnd(sig, i + 1);
        if (c == '(' || c == '{') {
          int k = i + 1;
          while (sig.charAt(k) != ')' && sig.charAt(k) != '}') k = typeEnd(sig, k);
          return k + 1;
        }
        return i + 1;
      }

      /** Sare peste valoarea al cărei tip începe la {@code sig[i]}; întoarce indexul de după tip. */
      int skip(String sig, int i) {
        char c = sig.charAt(i);
        switch (c) {
          case 'y' -> p += 1;
          case 'n', 'q' -> {
            align(2);
            p += 2;
          }
          case 'x', 't', 'd' -> {
            align(8);
            p += 8;
          }
          case 's', 'o' -> {
            int len = u32();
            p += len + 1;
          }
          case 'g' -> {
            int len = u8();
            p += len + 1;
          }
          case 'v' -> {
            String inner = sig();
            for (int k = 0; k < inner.length(); ) k = skip(inner, k);
          }
          case 'a' -> {
            int len = u32();
            align(alignOf(sig.charAt(i + 1)));
            p += len;
            return typeEnd(sig, i);
          }
          case '(', '{' -> {
            align(8);
            int k = i + 1;
            while (sig.charAt(k) != ')' && sig.charAt(k) != '}') k = skip(sig, k);
            return k + 1;
          }
          default -> {
            align(4);
            p += 4;
          }
        }
        return i + 1;
      }
    }

    /** Un mesaj primit. type: 1 = apel de metodă, 2 = răspuns, 3 = eroare, 4 = semnal. */
    static final class Msg {
      int type;
      int flags;
      int serial;
      int replySerial;
      String path;
      String iface;
      String member;
      String errorName;
      String sender;
      String sig = "";
      In body;
    }

    private final java.nio.channels.SocketChannel ch;
    private final Object writeLock = new Object();
    private final AtomicInteger serial = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Msg>> waiting = new ConcurrentHashMap<>();
    /** Apelurile de metodă și semnalele primite (rulează pe thread-ul de citire: nu are voie să aștepte răspunsuri). */
    volatile java.util.function.Consumer<Msg> onMessage;
    volatile Runnable onClosed;
    volatile String uniqueName;
    private volatile boolean closed;

    private DBus(java.nio.channels.SocketChannel ch) {
      this.ch = ch;
    }

    static String uid() {
      try {
        return String.valueOf(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
      } catch (Exception | LinkageError e) {
        try {
          Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
          String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
          if (out.matches("\\d+")) return out;
        } catch (IOException ignored) {
          // mai jos
        }
        return "0";
      }
    }

    /** Socketul sesiunii D-Bus (null dacă nu există unul pe care Java îl poate deschide). */
    static Path sessionSocket() {
      String addr = System.getenv("DBUS_SESSION_BUS_ADDRESS");
      if (addr != null && !addr.isBlank()) {
        for (String one : addr.split(";")) {
          if (!one.startsWith("unix:")) continue;
          for (String kv : one.substring(5).split(",")) {
            if (!kv.startsWith("path=")) continue;
            String v = kv.substring(5);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.length(); i++) { // valorile pot avea %XX
              char c = v.charAt(i);
              if (c == '%' && i + 2 < v.length()) {
                try {
                  sb.append((char) Integer.parseInt(v.substring(i + 1, i + 3), 16));
                  i += 2;
                  continue;
                } catch (RuntimeException e) {
                  // îl lăsăm ca atare
                }
              }
              sb.append(c);
            }
            return Path.of(sb.toString());
          }
        }
        return null; // socket "abstract" sau alt transport
      }
      Path def = Path.of("/run/user/" + uid() + "/bus");
      return Files.exists(def) ? def : null;
    }

    static DBus connect() throws IOException {
      Path sock = sessionSocket();
      if (sock == null) throw new IOException("fără sesiune D-Bus");
      java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
      try {
        ch.connect(java.net.UnixDomainSocketAddress.of(sock));
        StringBuilder hex = new StringBuilder();
        for (char c : uid().toCharArray()) hex.append(String.format("%02x", (int) c));
        writeAll(ch, ByteBuffer.wrap(("\0AUTH EXTERNAL " + hex + "\r\n").getBytes(StandardCharsets.US_ASCII)));
        StringBuilder line = new StringBuilder();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (line.length() < 512) {
          one.clear();
          if (ch.read(one) < 0) throw new IOException("D-Bus a închis conexiunea");
          char c = (char) one.get(0);
          if (c == '\n') break;
          line.append(c);
        }
        if (!line.toString().startsWith("OK")) throw new IOException("autentificare D-Bus refuzată");
        writeAll(ch, ByteBuffer.wrap("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII)));
        DBus bus = new DBus(ch);
        Thread t = new Thread(bus::readLoop, "dbus");
        t.setDaemon(true);
        t.start();
        Msg hello = bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "", null, 3000);
        bus.uniqueName = hello.body.str();
        return bus;
      } catch (IOException | RuntimeException e) {
        try {
          ch.close();
        } catch (IOException ignored) {
          // nimic
        }
        throw e instanceof IOException io ? io : new IOException(e);
      }
    }

    private static void writeAll(java.nio.channels.SocketChannel ch, ByteBuffer buf) throws IOException {
      while (buf.hasRemaining()) ch.write(buf);
    }

    private void readFully(byte[] dst, int off, int len) throws IOException {
      ByteBuffer buf = ByteBuffer.wrap(dst, off, len);
      while (buf.hasRemaining()) if (ch.read(buf) < 0) throw new java.io.EOFException();
    }

    private void readLoop() {
      try {
        while (!closed) {
          byte[] fixed = new byte[16];
          readFully(fixed, 0, 16);
          In f = new In(fixed, 4);
          int bodyLen = f.u32();
          int ser = f.u32();
          int fieldsLen = f.u32();
          if (fixed[0] != 'l' || bodyLen < 0 || fieldsLen < 0 || bodyLen > (64 << 20) || fieldsLen > (1 << 20)) {
            throw new IOException("mesaj D-Bus neașteptat");
          }
          byte[] h = Arrays.copyOf(fixed, 16 + ((fieldsLen + 7) & ~7));
          readFully(h, 16, h.length - 16);
          byte[] body = new byte[bodyLen];
          readFully(body, 0, bodyLen);
          Msg m = new Msg();
          try {
            m.type = fixed[1] & 0xff;
            m.flags = fixed[2] & 0xff;
            m.serial = ser;
            In in = new In(h, 16);
            int end = 16 + fieldsLen;
            while (in.p < end) {
              in.align(8);
              int code = in.u8();
              String vs = in.sig();
              switch (code) {
                case 1 -> m.path = in.str();
                case 2 -> m.iface = in.str();
                case 3 -> m.member = in.str();
                case 4 -> m.errorName = in.str();
                case 5 -> m.replySerial = in.u32();
                case 7 -> m.sender = in.str();
                case 8 -> m.sig = in.sig();
                default -> {
                  for (int k = 0; k < vs.length(); ) k = in.skip(vs, k);
                }
              }
            }
            m.body = new In(body, 0);
          } catch (RuntimeException e) {
            continue; // mesaj pe care nu-l înțelegem
          }
          if (m.type == 2 || m.type == 3) {
            CompletableFuture<Msg> w = waiting.remove(m.replySerial);
            if (w != null) w.complete(m);
          } else {
            java.util.function.Consumer<Msg> c = onMessage;
            if (c != null) {
              try {
                c.accept(m);
              } catch (RuntimeException e) {
                // tratarea unui mesaj nu are voie să oprească citirea
              }
            }
          }
        }
      } catch (IOException e) {
        // conexiune închisă
      } finally {
        boolean wasOpen = !closed;
        closed = true;
        try {
          ch.close();
        } catch (IOException ignored) {
          // nimic
        }
        for (CompletableFuture<Msg> w : waiting.values()) w.completeExceptionally(new IOException("D-Bus închis"));
        waiting.clear();
        Runnable r = onClosed;
        if (wasOpen && r != null) r.run();
      }
    }

    /** Trimite un mesaj; întoarce numărul lui de serie. */
    int send(int type, int flags, String path, String iface, String member, String errorName, int replySerial, String dest,
        String sig, Out body) throws IOException {
      synchronized (writeLock) {
        return send0(serial.incrementAndGet(), type, flags, path, iface, member, errorName, replySerial, dest, sig, body);
      }
    }

    private int send0(int ser, int type, int flags, String path, String iface, String member, String errorName, int replySerial,
        String dest, String sig, Out body) throws IOException {
      if (closed) throw new IOException("D-Bus închis");
      Out h = new Out();
      h.u8('l').u8(type).u8(flags).u8(1);
      h.u32(body == null ? 0 : body.n);
      h.u32(ser);
      int[] fields = h.array(8);
      if (path != null) h.struct().u8(1).sig("o").str(path);
      if (iface != null) h.struct().u8(2).sig("s").str(iface);
      if (member != null) h.struct().u8(3).sig("s").str(member);
      if (errorName != null) h.struct().u8(4).sig("s").str(errorName);
      if (replySerial != 0) h.struct().u8(5).sig("u").u32(replySerial);
      if (dest != null) h.struct().u8(6).sig("s").str(dest);
      if (sig != null && !sig.isEmpty()) h.struct().u8(8).sig("g").sig(sig);
      h.end(fields);
      h.align(8);
      if (body != null) h.raw(Arrays.copyOf(body.b, body.n));
      writeAll(ch, ByteBuffer.wrap(h.b, 0, h.n));
      return ser;
    }

    /** Apel de metodă cu așteptarea răspunsului. Nu se apelează de pe thread-ul de citire. */
    Msg call(String dest, String path, String iface, String member, String sig, Out body, long timeoutMs) throws IOException {
      CompletableFuture<Msg> f = new CompletableFuture<>();
      int ser;
      synchronized (writeLock) {
        ser = serial.incrementAndGet();
        waiting.put(ser, f);
        send0(ser, 1, 0, path, iface, member, null, 0, dest, sig, body);
      }
      try {
        Msg r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
        if (r.type == 3) throw new IOException(String.valueOf(r.errorName));
        return r;
      } catch (InterruptedException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        waiting.remove(ser);
        throw new IOException("D-Bus: fără răspuns la " + member);
      }
    }

    void reply(Msg to, String sig, Out body) throws IOException {
      if ((to.flags & 1) != 0) return; // apelantul nu așteaptă răspuns
      send(2, 1, null, null, null, null, to.serial, to.sender, sig, body);
    }

    void error(Msg to, String name, String text) throws IOException {
      if ((to.flags & 1) != 0) return;
      send(3, 1, null, null, null, name, to.serial, to.sender, "s", new Out().str(text == null ? "" : text));
    }

    void signal(String path, String iface, String member, String sig, Out body) throws IOException {
      send(4, 1, path, iface, member, null, 0, null, sig, body);
    }

    @Override
    public void close() {
      closed = true;
      try {
        ch.close();
      } catch (IOException ignored) {
        // nimic
      }
    }
  }

  private boolean windowHidden() {
    return !isVisible() || (getExtendedState() & ICONIFIED) != 0;
  }

  /** O compilare a reușit: dacă cea dinainte eșuase, iconița revine la normal (și anunțăm, dacă fereastra e ascunsă). */
  private void compileOk() {
    if (!lastCompileFailed) return;
    lastCompileFailed = false;
    updateTray();
    if (windowHidden()) balloon("Compilare reușită", "Clasele au ajuns în Tomcat.", java.awt.TrayIcon.MessageType.INFO);
  }

  /** O compilare a eșuat: iconița devine roșie, iar cu fereastra ascunsă apare și o notificare cu prima eroare. */
  private void compileFailed(String names, List<String> errors) {
    lastCompileFailed = true;
    updateTray();
    if (windowHidden()) {
      balloon("Eroare de compilare: " + names, errors.isEmpty() ? "Vezi log-ul programului." : errors.get(0),
          java.awt.TrayIcon.MessageType.ERROR);
    }
  }

  // ===================================================================================
  // Deploy: pornire / oprire
  // ===================================================================================
  private void toggleDeploy() {
    if (deployRunning) stopDeploy();
    else startDeploy();
  }

  private void startDeploy() {
    if (preparing || projectDir == null || tomcatHome == null) return;
    final String ctx = contextName();
    if (ctx == null) {
      JOptionPane.showMessageDialog(
          this,
          "Numele contextului e invalid. Folosește litere, cifre și caracterele _ . # - (sau lasă gol pentru ROOT).",
          "Context invalid",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    savePrefs();
    preparing = true;
    btnDeploy.setEnabled(false);
    btnDeploy.setText("Se pregătește deploy-ul...");
    final boolean cleanOthers = chkOthers.isSelected();
    final boolean confirm = chkConfirm.isSelected();
    final boolean fullRebuild = chkFull.isSelected();
    bg.submit(
        () -> {
          try {
            prepareDeploy(ctx, cleanOthers, confirm, fullRebuild);
            // încălzirea rulează pe thread-ul ei (nu pe cel de compilare), ca să poată ceda locul unei salvări reale
            scheduleWarm(this::warmClasspath);
            startWatching();
            final int gen = ++deployGen;
            eventsProven = false;
            eventsMissed = false;
            safetyDelay = POLL_SAFETY_MS;
            deployRunning = true;
            long poll = nativeEvents ? POLL_SAFETY_MS : POLL_ONLY_MS;
            watchTask = scheduler.schedule(() -> safetyTick(gen), poll, TimeUnit.MILLISECONDS);
            needFullScan = true; // ce s-a schimbat între pregătire și pornirea watcher-ului
            requestTick(0);
            log(
                "[INFO] Watcher pornit ("
                    + (nativeEvents ? "evenimente de sistem - reacție instant" : "sondare la " + poll + " ms")
                    + "): Java -> compilat automat, Web/JSP/resurse -> modificate pe loc.");
            SwingUtilities.invokeLater(() -> chkFull.setSelected(false)); // bifa e valabilă doar pentru un deploy
            setDeployUi(true);
          } catch (CancelledException e) {
            closeJavac();
            log("[INFO] Deploy anulat - nu am șters și nu am schimbat nimic în Tomcat.");
            setDeployUi(false);
          } catch (Exception e) {
            closeJavac();
            log("[EROARE] Deploy-ul nu a putut porni: " + e);
            setDeployUi(false);
          } finally {
            preparing = false;
          }
        });
  }

  private void stopDeploy() {
    deployRunning = false;
    deployGen++;
    ScheduledFuture<?> wt = watchTask;
    if (wt != null) wt.cancel(false);
    stopWatching();
    pending.clear();
    compiledSrc.clear();
    closeJavac();
    log("[INFO] Watcher & Deploy oprit.");
    setDeployUi(false);
  }

  private void closeJavac() {
    JavacSession s = javac;
    javac = null;
    if (s != null) compileExec.submit(s::close);
  }

  /**
   * Pregătește Tomcat-ul. Dacă proiectul e același cu la ultimul deploy reușit (și rezultatul încă există în
   * Tomcat), REIA de unde a rămas: nu curăță, nu rulează Maven, ci sincronizează doar diferențele.
   * Altfel face un deploy complet: curățare + Maven (clean compile war:exploded) + META-INF.
   */
  private void prepareDeploy(String ctx, boolean cleanOthers, boolean confirm, boolean fullRebuild) throws Exception {
    final Path project = projectDir;
    final Path tomcat = tomcatHome;
    context = ctx;
    target = tomcat.resolve("webapps").resolve(ctx);
    classesDir = target.resolve("WEB-INF").resolve("classes");
    javaSrc = project.resolve("src").resolve("main").resolve("java");
    final Path webappSrc = project.resolve("src").resolve("main").resolve("webapp");
    final Path pomFile = project.resolve("pom.xml");
    final boolean hasPom = Files.isRegularFile(pomFile);

    closeJavac();
    compiledSrc.clear();
    cpCache = null;
    javac = new JavacSession(); // un singur compilator, reutilizat pe toată durata deploy-ului
    javac.procNone = !needsAnnotationProcessing(pomFile);
    log("[JAVA] Annotation processing: " + (javac.procNone ? "oprit (mai rapid)" : "activ (Lombok/MapStruct etc. detectat în pom.xml)"));
    log("[DEPLOY] Proiect: " + project);

    pom = null;
    if (hasPom) {
      try {
        pom = PomInfo.read(project);
        pomRelease = pom.release;
        javac.sourceCharset = pom.charset(); // sursele se citesc cu encoding-ul din pom, ca la Maven
        depsFingerprint = depsFingerprint(pomFile);
        int jf = jdkFeature;
        if (jf > 0 && pom.release > jf) {
          log("[AVERTISMENT] Proiectul cere Java " + pom.release + ", dar pentru Tomcat ai ales Java " + jf
              + ": clasele nu vor putea fi încărcate. Alege un JDK mai nou din listă.");
        }
      } catch (Exception e) {
        log("[AVERTISMENT] Nu pot citi pom.xml: " + e);
      }
    }

    if (!fullRebuild && pom != null && canResume(project, tomcat, ctx)) {
      if (cleanOthers) log("[INFO] La reluare nu șterg alte aplicații. Bifează reconstruirea completă dacă vrei și asta.");
      resumeDeploy(project, webappSrc, pomFile);
    } else {
      fullDeploy(project, tomcat, ctx, cleanOthers, confirm, webappSrc, pomFile, hasPom);
    }
    log("[OK] Tomcat pregătit: " + target);
  }

  /** Deploy complet: curățenie (cu confirmare) + Maven + copiere + META-INF. */
  private void fullDeploy(
      Path project,
      Path tomcat,
      String ctx,
      boolean cleanOthers,
      boolean confirm,
      Path webappSrc,
      Path pomFile,
      boolean hasPom)
      throws Exception {
    prefs.remove(PREF_DEPLOYED); // nu mai e valabil cât timp reconstruim
    int port = httpPort(tomcat);
    if (portOpen(port)) {
      log("[AVERTISMENT] Tomcat rulează acum. Pentru o curățare completă oprește-l, apoi pornește-l după deploy.");
    }

    // 1. Curățenie: nimic de la proiectele vechi (cu confirmare, dacă e activată)
    List<String> toDelete = describeDeletion(tomcat, cleanOthers, ctx);
    if (confirm && !toDelete.isEmpty() && !confirmDelete(toDelete)) throw new CancelledException();
    cleanTomcat(tomcat, cleanOthers, ctx);
    Files.createDirectories(classesDir);

    // 2. Maven (ca la mvn clean package, dar exploded în target/)
    MavenResult mvn = new MavenResult(false, null, null);
    if (hasPom) {
      log("[MVN] Rulez: clean compile war:exploded (poate dura câteva secunde)...");
      try {
        mvn = runMaven(project, List.of("-B", "-V", "-DskipTests", "clean", "compile", "war:exploded"));
      } catch (IOException e) {
        log("[AVERTISMENT] Nu pot porni Maven (" + e.getMessage() + ").");
      }
      if (mvn.ok()) {
        Path exploded = findExploded(project);
        if (exploded != null) {
          log("[MVN] OK. Copiez structura din " + exploded.getFileName() + " în Tomcat (în paralel)...");
          copyTree(exploded, target);
        } else {
          log("[AVERTISMENT] Maven a rulat, dar nu am găsit folderul exploded (WEB-INF) în target/.");
        }
      }
    } else {
      log("[AVERTISMENT] Nu există pom.xml în proiect.");
    }
    if (!mvn.ok()) {
      log("[AVERTISMENT] Maven indisponibil/eșuat -> mod manual (copiez webapp/resources și compilez Java; WEB-INF/lib nu e populat).");
    }

    // 3. Baseline pentru watcher + completare cu ce lipsește
    webRoot = new SyncRoot(webappSrc, target, true);
    resRoots = buildResourceRoots(project, pom, classesDir);
    javaMtimes.clear();
    pending.clear();
    scanCopy(webRoot, ScanMode.FRESH, "[WEB]", null);
    for (SyncRoot r : resRoots) scanCopy(r, ScanMode.FRESH, "[RES]", null);
    scanJava(ScanMode.FRESH, null);
    if (!mvn.ok() && !javaMtimes.isEmpty()) {
      compile(new ArrayList<>(javaMtimes.keySet()));
    }

    // 4. META-INF ca la "mvn clean package"
    if (pom != null) {
      mavenVersion = mvn.mavenVersion();
      warVersion = resolveWarVersion(mvn.warVersion(), pom);
      writeMetaInf(target, pom, warVersion, mavenVersion);
      pomMtime = fileStamp(pomFile);
      log("[META-INF] MANIFEST.MF + maven/" + pom.groupId + "/" + pom.artifactId + " generate (WAR Plugin " + warVersion + ").");
    }
    // Memorăm deploy-ul doar dacă Maven a reușit: altfel data viitoare încercăm din nou build-ul complet.
    if (hasPom && mvn.ok() && pom != null) saveDeployRecord(project, tomcat, ctx);
  }

  /** Reluare: același proiect, același Tomcat, același context -> fără curățenie și fără Maven. */
  private void resumeDeploy(Path project, Path webappSrc, Path pomFile) throws Exception {
    log("[RESUME] Proiectul e același ca la ultimul deploy -> nu curăț și nu rulez Maven; continui de unde am rămas.");
    Files.createDirectories(classesDir);
    webRoot = new SyncRoot(webappSrc, target, true);
    resRoots = buildResourceRoots(project, pom, classesDir);
    javaMtimes.clear();
    pending.clear();

    // ce s-a schimbat cât programul era oprit: scriem doar diferențele, pe loc
    scanCopy(webRoot, ScanMode.RESUME, "[WEB]", null);
    for (SyncRoot r : resRoots) scanCopy(r, ScanMode.RESUME, "[RES]", null);
    scanJava(ScanMode.RESUME, null);
    List<Path> stale = staleJava();
    if (stale.isEmpty()) {
      log("[JAVA] Toate clasele sunt la zi.");
    } else {
      compile(stale);
    }

    // META-INF: păstrăm versiunile din deploy-ul anterior (nu avem output Maven acum)
    String[] prev = readPreviousMeta(target);
    warVersion = prev[0] != null ? prev[0] : resolveWarVersion(null, pom);
    mavenVersion = prev[1];
    writeMetaInf(target, pom, warVersion, mavenVersion);
    pomMtime = fileStamp(pomFile);

    // dependențele s-au schimbat cât programul era oprit? atunci actualizăm doar WEB-INF/lib
    if (!sha256(depsFingerprint).equals(prefs.get(PREF_DEPS_HASH, ""))) {
      log("[DEPS] Dependențele din pom.xml diferă de ultimul deploy.");
      refreshDependencies();
    }
  }

  private boolean canResume(Path project, Path tomcat, String ctx) {
    String rec = prefs.get(PREF_DEPLOYED, null);
    if (rec == null || !rec.equals(project + "\n" + tomcat + "\n" + ctx)) return false;
    Path t = tomcat.resolve("webapps").resolve(ctx);
    return Files.isRegularFile(t.resolve("META-INF").resolve("MANIFEST.MF")) && Files.isDirectory(t.resolve("WEB-INF"));
  }

  private void saveDeployRecord(Path project, Path tomcat, String ctx) {
    prefs.put(PREF_DEPLOYED, project + "\n" + tomcat + "\n" + ctx);
    prefs.put(PREF_DEPS_HASH, sha256(depsFingerprint));
    flushPrefs();
  }

  /** .java fără .class sau mai noi decât .class-ul lor (modificate cât programul era oprit). */
  private List<Path> staleJava() {
    List<Path> stale = new ArrayList<>();
    for (Map.Entry<Path, Stamp> e : javaMtimes.entrySet()) {
      Path j = e.getKey();
      String name = j.getFileName().toString();
      if (name.equals("package-info.java") || name.equals("module-info.java")) continue;
      String rel = javaSrc.relativize(j).toString();
      Path cls = classesDir.resolve(rel.substring(0, rel.length() - ".java".length()) + ".class");
      try {
        if (!Files.exists(cls) || Files.getLastModifiedTime(cls).toMillis() < e.getValue().mtime()) stale.add(j);
      } catch (IOException ex) {
        stale.add(j);
      }
    }
    return stale;
  }

  /** Versiunile scrise de deploy-ul anterior: {WAR plugin, Maven} (null dacă nu se găsesc). */
  static String[] readPreviousMeta(Path root) {
    String war = null;
    String mvn = null;
    try (InputStream in = Files.newInputStream(root.resolve("META-INF").resolve("MANIFEST.MF"))) {
      String cb = new Manifest(in).getMainAttributes().getValue("Created-By");
      String prefix = "Maven WAR Plugin ";
      if (cb != null && cb.startsWith(prefix)) war = cb.substring(prefix.length()).trim();
    } catch (IOException ignored) {
      // rămâne null
    }
    Path mavenDir = root.resolve("META-INF").resolve("maven");
    if (Files.isDirectory(mavenDir)) {
      try (Stream<Path> s = Files.walk(mavenDir, 3)) {
        Path pp = s.filter(p -> p.getFileName().toString().equals("pom.properties")).findFirst().orElse(null);
        if (pp != null) {
          String first = Files.readAllLines(pp).get(0);
          String prefix = "#Created by Apache Maven ";
          if (first.startsWith(prefix)) mvn = first.substring(prefix.length()).trim();
        }
      } catch (IOException | RuntimeException ignored) {
        // rămâne null
      }
    }
    return new String[] {war, mvn};
  }

  static String sha256(String text) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Amprentă simplă (oră de modificare + mărime) pentru un singur fișier. */
  static long fileStamp(Path p) throws IOException {
    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
    return a.lastModifiedTime().toMillis() * 31 + a.size();
  }

  // ===================================================================================
  // Curățare Tomcat
  // ===================================================================================
  /** Aruncată când utilizatorul refuză ștergerea. */
  static final class CancelledException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  /** Aplicațiile din webapps care ar fi șterse de "Șterge și alte aplicații" (fără cele standard și fără contextul curent). */
  static boolean isOtherApp(String name, String ctx) {
    return !STANDARD_WEBAPPS.contains(name) && !name.equals(ctx) && !name.equals(ctx + ".war");
  }

  static long countFiles(Path p) {
    try (Stream<Path> s = Files.walk(p)) {
      return s.filter(Files::isRegularFile).count();
    } catch (IOException e) {
      return -1;
    }
  }

  private static void addIfNotEmpty(List<String> out, String label, Path p) {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
      long n = countFiles(p);
      if (n != 0) out.add(label + " (" + (n < 0 ? "?" : String.valueOf(n)) + " fișiere)");
    } else {
      out.add(label);
    }
  }

  /** Ce urmează să fie șters - ca să putem cere confirmare. Gol = nimic de șters. */
  static List<String> describeDeletion(Path tomcat, boolean cleanOthers, String ctx) {
    List<String> out = new ArrayList<>();
    Path webapps = tomcat.resolve("webapps");
    Path workLocalhost = tomcat.resolve("work").resolve("Catalina").resolve("localhost");
    addIfNotEmpty(out, "webapps/" + ctx, webapps.resolve(ctx));
    addIfNotEmpty(out, "webapps/" + ctx + ".war", webapps.resolve(ctx + ".war"));
    addIfNotEmpty(out, "work/Catalina/localhost/" + ctx + " (JSP-uri compilate)", workLocalhost.resolve(ctx));
    if (cleanOthers && Files.isDirectory(webapps)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(webapps)) {
        for (Path p : ds) {
          String name = p.getFileName().toString();
          if (isOtherApp(name, ctx)) addIfNotEmpty(out, "webapps/" + name, p);
        }
      } catch (IOException ignored) {
        // lista e doar informativă
      }
    }
    return out;
  }

  /** Întreabă utilizatorul (apelat din thread-ul de fundal; dialogul rulează pe EDT). */
  private boolean confirmDelete(List<String> items) {
    final boolean[] ok = {false};
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            String msg = "Voi șterge definitiv din Tomcat:\n\n  \u2022 " + String.join("\n  \u2022 ", items) + "\n\nContinui?";
            ok[0] =
                JOptionPane.showConfirmDialog(
                        this, msg, "Confirmare ștergere", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    == JOptionPane.YES_OPTION;
          });
    } catch (Exception e) {
      return false;
    }
    return ok[0];
  }

  private void cleanTomcat(Path tomcat, boolean cleanOthers, String ctx) {
    log("[CLEAN] Șterg aplicația veche din Tomcat (" + ctx + ")...");
    Path webapps = tomcat.resolve("webapps");
    Path workLocalhost = tomcat.resolve("work").resolve("Catalina").resolve("localhost");
    int failed = 0;
    failed += deleteTree(webapps.resolve(ctx));
    failed += deleteTree(webapps.resolve(ctx + ".war"));
    failed += deleteTree(workLocalhost.resolve(ctx)); // JSP-uri compilate de proiectul vechi

    if (cleanOthers && Files.isDirectory(webapps)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(webapps)) {
        for (Path p : ds) {
          String name = p.getFileName().toString();
          if (!isOtherApp(name, ctx)) continue;
          String other = name.endsWith(".war") ? name.substring(0, name.length() - 4) : name;
          log("[CLEAN] Șterg webapps/" + name);
          failed += deleteTree(p);
          failed += deleteTree(workLocalhost.resolve(other));
        }
      } catch (IOException e) {
        log("[AVERTISMENT] Nu pot parcurge webapps/: " + e.getMessage());
      }
    }
    if (failed > 0) {
      log("[AVERTISMENT] " + failed + " fișiere nu s-au putut șterge (blocate?). Oprește Tomcat și reîncearcă.");
    }
  }

  /** Șterge recursiv; întoarce numărul de elemente care n-au putut fi șterse. */
  static int deleteTree(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
    int[] failed = {0};
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
              try {
                Files.delete(f);
              } catch (IOException e) {
                failed[0]++;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) {
              try {
                Files.delete(d);
              } catch (IOException ex) {
                failed[0]++;
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      failed[0]++;
    }
    return failed[0];
  }

  // ===================================================================================
  // Maven
  // ===================================================================================
  record MavenResult(boolean ok, String warVersion, String mavenVersion) {}

  private MavenResult runMaven(Path project, List<String> mavenArgs) throws IOException, InterruptedException {
    boolean wrapper = Files.isRegularFile(project.resolve(WINDOWS ? "mvnw.cmd" : "mvnw"));
    List<String> cmd = new ArrayList<>();
    if (WINDOWS) {
      cmd.add("cmd.exe");
      cmd.add("/c");
      cmd.add(wrapper ? project.resolve("mvnw.cmd").toString() : "mvn");
    } else if (wrapper) {
      cmd.add("sh");
      cmd.add(project.resolve("mvnw").toString());
    } else {
      cmd.add("mvn");
    }
    // -V ne dă versiunea de Maven; fără -q ca să putem citi versiunea exactă a war-plugin din output
    cmd.addAll(mavenArgs);

    ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile()).redirectErrorStream(true);
    applyJavaEnv(pb);
    Process p = pb.start();

    String warV = null;
    String mvnV = null;
    Charset cs;
    try {
      cs = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
    } catch (Exception e) {
      cs = Charset.defaultCharset();
    }
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
      String line;
      while ((line = r.readLine()) != null) {
        Matcher m = WAR_VERSION.matcher(line);
        if (m.find()) warV = m.group(1);
        if (mvnV == null) {
          Matcher mm = MAVEN_VERSION.matcher(line);
          if (mm.find()) mvnV = mm.group(1);
        }
        if (line.contains("[ERROR]") || line.contains("BUILD ")) log("[MVN] " + line.strip());
      }
    }
    boolean ok = p.waitFor() == 0;
    return new MavenResult(ok, warV, mvnV);
  }

  /** target/<finalName>/ - folderul cu WEB-INF creat de war:exploded. */
  static Path findExploded(Path project) throws IOException {
    Path t = project.resolve("target");
    if (!Files.isDirectory(t)) return null;
    try (Stream<Path> s = Files.list(t)) {
      return s.filter(Files::isDirectory)
          .filter(d -> !d.getFileName().toString().equals("classes") && Files.isDirectory(d.resolve("WEB-INF")))
          .findFirst()
          .orElse(null);
    }
  }

  /** Copiere paralelă a unui întreg arbore. */
  private void copyTree(Path from, Path to) throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.walk(from)) {
      files = s.filter(Files::isRegularFile).toList();
    }
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (Path f : files) {
      futures.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  Path dest = to.resolve(from.relativize(f));
                  syncInPlace(f, dest);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              },
              copyPool));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    log("[OK] " + files.size() + " fișiere copiate.");
  }


  // ===================================================================================
  // Scriere "pe loc": fișierul din Tomcat nu e șters și recreat, ci modificat doar unde diferă
  // ===================================================================================
  /**
   * Aduce {@code dest} la conținutul {@code data} modificând fișierul existent pe loc: compară bucată cu
   * bucată și scrie doar bucățile diferite, apoi taie coada dacă fișierul s-a scurtat. Fișierul rămâne
   * același (nu e șters), iar dacă nu s-a schimbat nimic nu se scrie nimic.
   *
   * @return true dacă s-a modificat ceva
   */
  static boolean writeInPlace(byte[] data, Path dest) throws IOException {
    if (Files.notExists(dest)) {
      Files.createDirectories(dest.getParent());
      Files.write(dest, data);
      return true;
    }
    boolean changed = false;
    try (FileChannel ch = FileChannel.open(dest, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long oldSize = ch.size();
      ByteBuffer buf = ByteBuffer.allocate(CHUNK);
      for (int off = 0; off < data.length; off += CHUNK) {
        int len = Math.min(CHUNK, data.length - off);
        boolean same = false;
        if (off + (long) len <= oldSize) {
          buf.clear();
          buf.limit(len);
          int r = 0;
          while (r < len) {
            int n = ch.read(buf, off + (long) r);
            if (n < 0) break;
            r += n;
          }
          same = r == len && Arrays.equals(buf.array(), 0, len, data, off, off + len);
        }
        if (!same) {
          ByteBuffer w = ByteBuffer.wrap(data, off, len);
          long pos = off;
          while (w.hasRemaining()) pos += ch.write(w, pos);
          changed = true;
        }
      }
      if (oldSize > data.length) {
        ch.truncate(data.length);
        changed = true;
      }
    }
    return changed;
  }

  /** De la această mărime în sus, un fișier nou e copiat direct de sistemul de operare (fără să treacă prin memoria programului). */
  static final long DIRECT_COPY_MIN = 256 * 1024;

  /**
   * Ca {@link #writeInPlace}, dar sursa e un fișier. Fișierele mari și noi (jar-uri la primul deploy) le copiază direct
   * sistemul de operare; cele foarte mari care există deja se compară în flux și se scrie doar ce diferă.
   */
  static boolean syncInPlace(Path src, Path dest) throws IOException {
    long size = Files.size(src);
    if (size > DIRECT_COPY_MIN && Files.notExists(dest)) {
      Files.createDirectories(dest.getParent());
      try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
          FileChannel out = FileChannel.open(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        long n = in.size();
        long pos = 0;
        while (pos < n) {
          long t = in.transferTo(pos, n - pos, out);
          if (t <= 0) break;
          pos += t;
        }
        if (pos == n) return true;
      } catch (java.nio.file.FileAlreadyExistsException e) {
        // a apărut între timp: îl aducem la zi pe loc, mai jos
      }
    }
    if (size <= MAX_IN_MEMORY) return writeInPlace(Files.readAllBytes(src), dest);
    return streamInPlace(src, dest);
  }

  /** Fișiere foarte mari: comparăm și scriem bucată cu bucată, pe loc - un jar de 50 MB identic nu mai e rescris. */
  static boolean streamInPlace(Path src, Path dest) throws IOException {
    if (Files.notExists(dest)) {
      Files.createDirectories(dest.getParent());
      Files.createFile(dest);
    }
    boolean changed = false;
    try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
        FileChannel out = FileChannel.open(dest, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long oldSize = out.size();
      ByteBuffer a = ByteBuffer.allocate(1 << 20);
      ByteBuffer b = ByteBuffer.allocate(1 << 20);
      long pos = 0;
      while (true) {
        a.clear();
        int n = readFully(in, a, pos);
        if (n <= 0) break;
        a.flip();
        boolean same = false;
        if (pos + n <= oldSize) {
          b.clear();
          b.limit(n);
          int r = readFully(out, b, pos);
          b.flip();
          same = r == n && a.equals(b);
        }
        if (!same) {
          long wp = pos;
          while (a.hasRemaining()) wp += out.write(a, wp);
          changed = true;
        }
        pos += n;
      }
      if (oldSize != pos) {
        out.truncate(pos);
        changed = true;
      }
    }
    return changed;
  }

  private static int readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
    int total = 0;
    while (buf.hasRemaining()) {
      int n = ch.read(buf, pos + total);
      if (n < 0) break;
      total += n;
    }
    return total;
  }

  // ===================================================================================
  // Resurse: foldere din pom, include/exclude și filtrare ${...} / @...@ (ca maven-resources-plugin)
  // ===================================================================================
  /** Câte un SyncRoot pentru fiecare folder de resurse din pom (implicit src/main/resources). */
  static List<SyncRoot> buildResourceRoots(Path project, PomInfo pom, Path classesDir) {
    List<SyncRoot> roots = new ArrayList<>();
    if (pom == null || pom.resources.isEmpty()) {
      roots.add(new SyncRoot(project.resolve("src").resolve("main").resolve("resources"), classesDir, false));
      return roots;
    }
    for (PomInfo.ResourceDir r : pom.resources) {
      roots.add(new SyncRoot(r.dir, classesDir, false, r.filtering, r.includes, r.excludes));
    }
    return roots;
  }

  /** Înlocuiește ${cheie} și @cheie@ cu valorile din pom; cheile necunoscute rămân neschimbate. */
  static byte[] filterContent(Path file, byte[] data, PomInfo pom) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot >= 0 && NON_FILTERED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) return data;
    for (byte b : data) if (b == 0) return data; // fișier binar
    Charset cs = pom.charset();
    String text;
    try {
      text =
          cs.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(data))
              .toString();
    } catch (Exception e) {
      return data; // nu e text în encoding-ul proiectului: îl lăsăm neatins
    }
    Matcher m = FILTER_TOKEN.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String key = m.group(1) != null ? m.group(1) : m.group(2);
      String val = filterValue(key, pom);
      m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group()));
    }
    m.appendTail(sb);
    return sb.toString().getBytes(cs);
  }

  static String filterValue(String key, PomInfo pom) {
    String v = pom.props.get(key);
    if (v != null) return PomInfo.resolve(v, pom.props);
    if (key.equals("maven.build.timestamp")) {
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
          .withZone(ZoneOffset.UTC)
          .format(Instant.now());
    }
    if (key.startsWith("env.")) return System.getenv(key.substring(4));
    return System.getProperty(key);
  }

  // ===================================================================================
  // Dependențe: dacă s-au schimbat în pom.xml, actualizăm WEB-INF/lib
  // ===================================================================================
  /** Amprenta părților din pom care influențează dependențele (parent, properties, dependencies...). */
  static String depsFingerprint(Path pomFile) throws Exception {
    Element root = parseXml(pomFile).getDocumentElement();
    StringBuilder sb = new StringBuilder();
    for (String n : List.of("parent", "properties", "dependencies", "dependencyManagement", "repositories")) {
      Element e = child(root, n);
      if (e != null) dump(e, sb);
    }
    return sb.toString();
  }

  private static void dump(Node n, StringBuilder sb) {
    if (n instanceof Element e) {
      sb.append('<').append(e.getNodeName()).append('>');
      for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) dump(c, sb);
      sb.append("</>");
    } else if (n.getNodeType() == Node.TEXT_NODE) {
      String t = n.getTextContent().trim();
      if (!t.isEmpty()) sb.append(t);
    }
  }

  /**
   * Rulează Maven (dependency:copy-dependencies) într-un folder temporar și aduce WEB-INF/lib la zi:
   * adaugă/actualizează jar-urile noi, șterge pe cele dispărute. Jar-urile proprii din
   * src/main/webapp/WEB-INF/lib nu se ating. Rulează pe thread-ul de compilare, ca să nu se suprapună.
   */
  private void refreshDependencies() {
    final Path project = projectDir;
    final Path lib = target.resolve("WEB-INF").resolve("lib");
    final Path tmp = project.resolve("target").resolve("hotdeps");
    log("[DEPS] Dependențele din pom.xml s-au schimbat -> actualizez WEB-INF/lib (Maven)...");
    JavacSession js = javac;
    if (js != null) js.invalidate(); // pe Windows jar-urile deschise nu pot fi șterse/înlocuite
    cpCache = null;
    try {
      deleteTree(tmp);
      Files.createDirectories(tmp);
      MavenResult r =
          runMaven(
              project,
              List.of("-B", "-DincludeScope=runtime", "-DoutputDirectory=" + tmp, "dependency:copy-dependencies"));
      if (!r.ok()) {
        log("[EROARE] Maven nu a putut actualiza dependențele. WEB-INF/lib a rămas neschimbat.");
        return;
      }
      Set<String> own = new HashSet<>();
      Path ownLib = project.resolve("src").resolve("main").resolve("webapp").resolve("WEB-INF").resolve("lib");
      if (Files.isDirectory(ownLib)) {
        try (Stream<Path> s = Files.list(ownLib)) {
          s.forEach(p -> own.add(p.getFileName().toString()));
        }
      }
      Files.createDirectories(lib);
      Set<String> wanted = new HashSet<>();
      int added = 0;
      int removed = 0;
      try (Stream<Path> s = Files.list(tmp)) {
        for (Path jar : s.filter(p -> p.toString().endsWith(".jar")).toList()) {
          String name = jar.getFileName().toString();
          wanted.add(name);
          try {
            if (syncInPlace(jar, lib.resolve(name))) {
              added++;
              log("[DEPS] + " + name);
            }
          } catch (IOException e) {
            log("[EROARE] Nu pot scrie " + name + ": " + e.getMessage() + " (oprește Tomcat și reîncearcă)");
          }
        }
      }
      try (Stream<Path> s = Files.list(lib)) {
        for (Path jar : s.filter(p -> p.toString().endsWith(".jar")).toList()) {
          String name = jar.getFileName().toString();
          if (wanted.contains(name) || own.contains(name)) continue;
          try {
            Files.delete(jar);
            removed++;
            log("[DEPS] - " + name);
          } catch (IOException e) {
            log("[EROARE] Nu pot șterge " + name + ": " + e.getMessage() + " (oprește Tomcat și reîncearcă)");
          }
        }
      }
      log("[OK] WEB-INF/lib actualizat (" + added + " adăugate/modificate, " + removed + " șterse). Repornește Tomcat ca să le încarce.");
      prefs.put(PREF_DEPS_HASH, sha256(depsFingerprint));
      flushPrefs();
    } catch (Exception e) {
      log("[EROARE] Actualizarea dependențelor: " + e);
    } finally {
      deleteTree(tmp);
    }
  }

  // ===================================================================================
  // META-INF (ca la mvn clean package)
  // ===================================================================================
  /** Versiunea war-plugin: 1) cea folosită de Maven, 2) din pom/parent, 3) din ~/.m2, 4) implicit. */
  static String resolveWarVersion(String fromMaven, PomInfo pom) {
    if (fromMaven != null) return fromMaven;
    if (pom != null && pom.warPluginVersion != null) return pom.warPluginVersion;
    String m2 = warVersionFromM2();
    return m2 != null ? m2 : DEFAULT_WAR_PLUGIN;
  }

  static String warVersionFromM2() {
    Path dir =
        Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "apache", "maven", "plugins", "maven-war-plugin");
    if (!Files.isDirectory(dir)) return null;
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(v -> Files.exists(dir.resolve(v).resolve("maven-war-plugin-" + v + ".jar")))
          .max(TomcatDeploy::compareVersions)
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  static int compareVersions(String a, String b) {
    String[] x = a.split("[.\\-]");
    String[] y = b.split("[.\\-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      String p = i < x.length ? x[i] : "0";
      String q = i < y.length ? y[i] : "0";
      int c =
          p.matches("\\d{1,9}") && q.matches("\\d{1,9}")
              ? Integer.compare(Integer.parseInt(p), Integer.parseInt(q))
              : p.compareTo(q);
      if (c != 0) return c;
    }
    return 0;
  }

  /**
   * Scrie în {@code root} (webapps/ROOT):
   *   META-INF/MANIFEST.MF
   *   META-INF/maven/&lt;groupId&gt;/&lt;artifactId&gt;/pom.xml
   *   META-INF/maven/&lt;groupId&gt;/&lt;artifactId&gt;/pom.properties
   */
  static void writeMetaInf(Path root, PomInfo pom, String warVersion, String mavenVersion) throws IOException {
    Path meta = root.resolve("META-INF");
    Files.createDirectories(meta);

    Manifest mf = new Manifest();
    Attributes a = mf.getMainAttributes();
    a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    a.put(new Attributes.Name("Created-By"), "Maven WAR Plugin " + warVersion);
    a.put(new Attributes.Name("Java-Version"), pom.javaVersion);
    a.put(new Attributes.Name("Build-Jdk-Spec"), pom.javaVersion);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    mf.write(bos); // format corect, cu \r\n și linie goală la final
    writeInPlace(bos.toByteArray(), meta.resolve("MANIFEST.MF"));

    if (pom.groupId != null && pom.artifactId != null) {
      Path mdir = meta.resolve("maven").resolve(pom.groupId).resolve(pom.artifactId);
      Files.createDirectories(mdir);
      syncInPlace(pom.pomFile, mdir.resolve("pom.xml"));
      String props =
          "#Created by Apache Maven" + (mavenVersion != null ? " " + mavenVersion : "") + "\n"
              + "artifactId=" + pom.artifactId + "\n"
              + "groupId=" + pom.groupId + "\n"
              + "version=" + pom.version + "\n";
      writeInPlace(props.getBytes(StandardCharsets.UTF_8), mdir.resolve("pom.properties"));
    }
  }

  // ===================================================================================
  // pom.xml
  // ===================================================================================
  static final class PomInfo {
    Path pomFile;
    String groupId;
    String artifactId;
    String version;
    String javaVersion; // "17"
    int release; // 17, sau 0 dacă nu e cunoscut
    String warPluginVersion; // null dacă pom-ul nu o declară
    Map<String, String> props = new HashMap<>(); // proprietăți + project.* (pentru filtrarea resurselor)
    String encoding = "UTF-8";
    final List<ResourceDir> resources = new ArrayList<>();

    /** Un folder din <build><resources> (ca în maven-resources-plugin). */
    static final class ResourceDir {
      final Path dir;
      final boolean filtering;
      final List<String> includes;
      final List<String> excludes;

      ResourceDir(Path dir, boolean filtering, List<String> includes, List<String> excludes) {
        this.dir = dir;
        this.filtering = filtering;
        this.includes = includes;
        this.excludes = excludes;
      }
    }

    Charset charset() {
      try {
        return Charset.forName(encoding);
      } catch (Exception e) {
        return StandardCharsets.UTF_8;
      }
    }

    static List<String> childTexts(Element parent, String container, String item) {
      List<String> out = new ArrayList<>();
      Element c = child(parent, container);
      if (c == null) return out;
      for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e && item.equals(e.getNodeName())) {
          String t = e.getTextContent().trim();
          if (!t.isEmpty()) out.add(t);
        }
      }
      return out;
    }

    /** Citește pom.xml (+ pom-urile părinte locale, ca să găsească proprietăți și versiuni de plugin). */
    static PomInfo read(Path project) throws Exception {
      PomInfo info = new PomInfo();
      info.pomFile = project.resolve("pom.xml");

      List<Element> chain = new ArrayList<>(); // copil primul
      Path cur = info.pomFile;
      for (int i = 0; i < 6 && cur != null && Files.isRegularFile(cur); i++) {
        Element root = parseXml(cur).getDocumentElement();
        chain.add(root);
        Element parent = child(root, "parent");
        if (parent == null) break;
        Element rp = child(parent, "relativePath");
        String rel = rp == null ? "../pom.xml" : rp.getTextContent().trim();
        if (rp != null && rel.isEmpty()) break; // <relativePath/> = fără părinte local
        Path next = cur.getParent().resolve(rel).normalize();
        if (Files.isDirectory(next)) next = next.resolve("pom.xml");
        cur = next;
      }
      if (chain.isEmpty()) throw new IOException("pom.xml lipsește: " + info.pomFile);

      Element self = chain.get(0);
      Element parentEl = child(self, "parent");
      Map<String, String> props = new HashMap<>();
      for (int i = chain.size() - 1; i >= 0; i--) { // părintele întâi, copilul suprascrie
        Element pr = child(chain.get(i), "properties");
        if (pr == null) continue;
        for (Node n = pr.getFirstChild(); n != null; n = n.getNextSibling()) {
          if (n instanceof Element e) props.put(e.getNodeName(), e.getTextContent().trim());
        }
      }

      String g = text(self, "groupId");
      String v = text(self, "version");
      if (parentEl != null) {
        if (g == null) g = text(parentEl, "groupId");
        if (v == null) v = text(parentEl, "version");
      }
      String a = text(self, "artifactId");
      props.put("project.groupId", String.valueOf(g));
      props.put("project.artifactId", String.valueOf(a));
      props.put("project.version", String.valueOf(v));
      props.put("pom.version", String.valueOf(v));
      info.groupId = resolve(g, props);
      info.artifactId = resolve(a, props);
      info.version = resolve(v, props);

      // Versiunea de Java: configurația plugin-ului are prioritate, apoi proprietățile
      String jv = pluginConfig(chain, "maven-compiler-plugin", List.of("release", "target", "source"), props);
      if (jv == null) {
        for (String k : List.of("maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version")) {
          if (props.containsKey(k)) {
            jv = resolve(props.get(k), props);
            break;
          }
        }
      }
      jv = normalizeJava(jv);
      if (jv == null) {
        info.javaVersion = String.valueOf(Runtime.version().feature());
        info.release = 0;
      } else {
        info.javaVersion = jv;
        info.release = Integer.parseInt(jv);
      }

      info.warPluginVersion = pluginVersion(chain, "maven-war-plugin", props);

      // proprietăți suplimentare, folosite la filtrarea resurselor
      String baseDir = project.toAbsolutePath().toString();
      props.putIfAbsent("project.basedir", baseDir);
      props.putIfAbsent("basedir", baseDir);
      props.putIfAbsent("project.build.directory", project.resolve("target").toAbsolutePath().toString());
      Element buildEl = child(self, "build");
      String finalName = buildEl == null ? null : text(buildEl, "finalName");
      props.putIfAbsent(
          "project.build.finalName", finalName != null ? resolve(finalName, props) : info.artifactId + "-" + info.version);
      String nm = text(self, "name");
      props.putIfAbsent("project.name", nm != null ? resolve(nm, props) : String.valueOf(info.artifactId));
      String ds = text(self, "description");
      if (ds != null) props.putIfAbsent("project.description", resolve(ds, props));
      String pk = text(self, "packaging");
      props.putIfAbsent("project.packaging", pk != null ? pk : "jar");
      info.props = props;
      info.encoding = props.getOrDefault("project.build.sourceEncoding", "UTF-8");

      // foldere de resurse: primul <build><resources> din lanțul de pom-uri (copilul înlocuiește părintele)
      Element resEl = null;
      for (Element p : chain) {
        Element b = child(p, "build");
        Element rs = b == null ? null : child(b, "resources");
        if (rs != null) {
          resEl = rs;
          break;
        }
      }
      Path defaultRes = project.resolve("src").resolve("main").resolve("resources");
      if (resEl == null) {
        info.resources.add(new ResourceDir(defaultRes, false, List.of(), List.of()));
      } else {
        for (Node n = resEl.getFirstChild(); n != null; n = n.getNextSibling()) {
          if (!(n instanceof Element r) || !"resource".equals(r.getNodeName())) continue;
          String dir = resolve(text(r, "directory"), props);
          Path d = dir == null ? defaultRes : project.resolve(dir).normalize();
          boolean filtering = "true".equalsIgnoreCase(resolve(text(r, "filtering"), props));
          info.resources.add(
              new ResourceDir(d, filtering, childTexts(r, "includes", "include"), childTexts(r, "excludes", "exclude")));
        }
      }
      return info;
    }

    /** "1.8" -> "8", "17" -> "17", altceva (ex: ${necunoscut}) -> null. */
    static String normalizeJava(String s) {
      if (s == null) return null;
      s = s.trim();
      if (s.startsWith("1.")) s = s.substring(2);
      return s.matches("\\d{1,2}") ? s : null;
    }

    static String pluginVersion(List<Element> chain, String artifactId, Map<String, String> props) {
      for (Element project : chain) {
        for (Element plugins : pluginContainers(project)) {
          Element p = pluginIn(plugins, artifactId);
          String v = p == null ? null : resolve(text(p, "version"), props);
          if (v != null && !v.contains("${")) return v;
        }
      }
      return null;
    }

    static String pluginConfig(List<Element> chain, String artifactId, List<String> keys, Map<String, String> props) {
      for (Element project : chain) {
        for (Element plugins : pluginContainers(project)) {
          Element p = pluginIn(plugins, artifactId);
          Element cfg = p == null ? null : child(p, "configuration");
          if (cfg == null) continue;
          for (String k : keys) {
            String v = resolve(text(cfg, k), props);
            if (v != null && !v.contains("${")) return v;
          }
        }
      }
      return null;
    }

    private static List<Element> pluginContainers(Element project) {
      List<Element> out = new ArrayList<>();
      Element build = child(project, "build");
      if (build == null) return out;
      Element plugins = child(build, "plugins");
      if (plugins != null) out.add(plugins);
      Element pm = child(build, "pluginManagement");
      Element pmPlugins = pm == null ? null : child(pm, "plugins");
      if (pmPlugins != null) out.add(pmPlugins);
      return out;
    }

    private static Element pluginIn(Element plugins, String artifactId) {
      for (Node n = plugins.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e && "plugin".equals(e.getNodeName()) && artifactId.equals(text(e, "artifactId"))) {
          return e;
        }
      }
      return null;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    static String resolve(String s, Map<String, String> props) {
      if (s == null) return null;
      for (int i = 0; i < 10; i++) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
          String val = props.get(m.group(1));
          if (val != null) changed = true;
          m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group()));
        }
        m.appendTail(sb);
        s = sb.toString();
        if (!changed) break;
      }
      return s;
    }
  }

  static Document parseXml(Path p) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setValidating(false);
    f.setXIncludeAware(false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    return f.newDocumentBuilder().parse(p.toFile());
  }

  static Element child(Element parent, String name) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && name.equals(e.getNodeName())) return e;
    }
    return null;
  }

  static String text(Element parent, String name) {
    Element c = child(parent, name);
    if (c == null) return null;
    String t = c.getTextContent().trim();
    return t.isEmpty() ? null : t;
  }

  // ===================================================================================
  // Watcher
  // ===================================================================================
  /** O pereche sursă -> destinație care se sincronizează. */
  static final class SyncRoot {
    final Path src;
    final Path dest;
    final boolean guardMetaInf; // nu suprascrie/șterge MANIFEST.MF și META-INF/maven generate de noi
    final boolean filtering; // înlocuiește ${...} / @...@ cu valorile din pom
    final List<PathMatcher> includes = new ArrayList<>();
    final List<PathMatcher> excludes = new ArrayList<>();
    final Map<Path, Stamp> mtimes = new ConcurrentHashMap<>();

    SyncRoot(Path src, Path dest, boolean guardMetaInf) {
      this(src, dest, guardMetaInf, false, List.of(), List.of());
    }

    SyncRoot(Path src, Path dest, boolean guardMetaInf, boolean filtering, List<String> inc, List<String> exc) {
      this.src = src;
      this.dest = dest;
      this.guardMetaInf = guardMetaInf;
      this.filtering = filtering;
      addMatchers(includes, inc);
      addMatchers(excludes, exc);
    }

    /** Pattern-uri în stil Maven/Ant: "**" + "/*.xml" potrivește și fișierele din rădăcină. */
    private static void addMatchers(List<PathMatcher> out, List<String> patterns) {
      for (String p : patterns) {
        p = p.trim().replace('\\', '/');
        if (p.endsWith("/")) p += "**";
        out.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
        if (p.startsWith("**/")) out.add(FileSystems.getDefault().getPathMatcher("glob:" + p.substring(3)));
      }
    }

    /** Respectă <includes>/<excludes> din pom (fără ele, acceptă tot). */
    boolean accepts(Path rel) {
      if (!includes.isEmpty() && includes.stream().noneMatch(m -> m.matches(rel))) return false;
      return excludes.stream().noneMatch(m -> m.matches(rel));
    }

    Path destOf(Path file) {
      return dest.resolve(src.relativize(file));
    }

    boolean isProtected(Path rel) {
      if (!guardMetaInf) return false;
      String s = rel.toString().replace('\\', '/');
      return s.equals("META-INF/MANIFEST.MF") || s.startsWith("META-INF/maven/");
    }
  }

  /** Amprenta unui fișier: dacă se schimbă ceva (ora de modificare SAU mărimea), îl considerăm modificat. */
  record Stamp(long mtime, long size) {
    static Stamp of(BasicFileAttributes a) {
      return new Stamp(a.lastModifiedTime().toMillis(), a.size());
    }
  }

  enum ScanMode {
    /** Scanare obișnuită: doar ce s-a schimbat față de ultima verificare. */
    LIVE,
    /** După un deploy proaspăt: memorăm starea și copiem doar ce lipsește (restul a venit de la Maven). */
    FRESH,
    /** Reluare: comparăm tot cu ce e în Tomcat și scriem doar diferențele. */
    RESUME
  }

  // -----------------------------------------------------------------------------------
  // Evenimente de sistem (inotify pe Linux, ReadDirectoryChangesW pe Windows)
  // -----------------------------------------------------------------------------------
  /**
   * Înregistrează folderele sursă la sistemul de operare, ca să fim anunțați INSTANT când se salvează ceva
   * (fără sondare la câteva sute de ms). Dacă nu merge (ex: macOS sondează lent, sau limita inotify), rămâne
   * sondarea rapidă.
   */
  private void startWatching() {
    stopWatching();
    nativeEvents = false;
    try {
      WatchService ws = FileSystems.getDefault().newWatchService();
      if (ws.getClass().getSimpleName().contains("Polling")) { // implementarea implicită de pe macOS: lentă
        ws.close();
        return;
      }
      watchService = ws;
      watching = true;
      Thread t = new Thread(this::watchLoop, "watch");
      t.setDaemon(true);
      t.start();
      List<Path> roots = new ArrayList<>();
      roots.add(javaSrc);
      if (webRoot != null) roots.add(webRoot.src);
      for (SyncRoot r : resRoots) roots.add(r.src);
      for (Path r : roots) if (Files.isDirectory(r)) registerTree(r);
      registerDir(projectDir); // doar folderul proiectului (nerecursiv), pentru pom.xml
      nativeEvents = true;
    } catch (IOException | RuntimeException e) {
      stopWatching();
      log("[INFO] Evenimentele de sistem nu sunt disponibile (" + e.getMessage() + ") -> sondare rapidă.");
    }
  }

  private void registerTree(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
            if (!d.equals(root) && SKIP_DIRS.contains(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
            registerDir(d);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path f, IOException e) {
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void registerDir(Path dir) throws IOException {
    WatchService ws = watchService;
    if (ws == null) return;
    WatchKey key =
        dir.register(
            ws,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
    watchDirs.put(key, dir);
  }

  private void stopWatching() {
    watching = false;
    WatchService ws = watchService;
    watchService = null;
    if (ws != null) {
      try {
        ws.close();
      } catch (IOException ignored) {
        // nimic
      }
    }
    watchDirs.clear();
    dirtyFiles.clear();
    newDirs.clear();
    pendingDeletes.clear();
    scanFound.clear();
    needFullScan = false;
    pomDirty = false;
  }

  /**
   * Thread-ul de evenimente. Nu mai așteaptă nimic: ia tot ce e deja în coadă și procesează pe loc, pe acest thread
   * (fără "perioadă de liniște" și fără predare către alt thread). Salvările prinse la jumătate sunt tratate în
   * {@link #processDirty}: un fișier trunchiat la 0 octeți e amânat până sosește conținutul.
   */
  private void watchLoop() {
    final WatchService ws = watchService;
    if (ws == null) return;
    while (watching) {
      boolean wake;
      try {
        wake = drainKey(ws.take());
        WatchKey k;
        while ((k = ws.poll()) != null) wake |= drainKey(k);
      } catch (InterruptedException | ClosedWatchServiceException e) {
        return;
      }
      if (wake) tick(false);
    }
  }

  /** Fișiere temporare ale editoarelor (salvare atomică, swap, lock): nu le trimitem în Tomcat din evenimente. */
  static boolean isEditorTemp(Path p) {
    Path fn = p.getFileName();
    if (fn == null) return false;
    String n = fn.toString();
    return n.endsWith("___jb_tmp___") || n.endsWith("___jb_old___") || n.endsWith("~") || n.startsWith(".goutputstream-")
        || n.endsWith(".swp") || n.endsWith(".swx") || n.endsWith(".swo") || n.endsWith(".kate-swp") || n.equals("4913")
        || n.startsWith(".#") || (n.startsWith("#") && n.endsWith("#")) || n.startsWith("~$") || n.startsWith(".~lock.");
  }

  /** Notează ce s-a schimbat pentru cheia dată. @return true dacă e ceva de procesat */
  private boolean drainKey(WatchKey key) {
    boolean wake = false;
    Path dir = watchDirs.get(key);
    if (dir == null) {
      key.pollEvents();
    } else {
      for (WatchEvent<?> ev : key.pollEvents()) {
        WatchEvent.Kind<?> kind = ev.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) {
          needFullScan = true;
          wake = true;
          continue;
        }
        Path p = dir.resolve((Path) ev.context());
        if (dir.equals(projectDir)) { // în folderul proiectului ne interesează doar pom.xml
          if ("pom.xml".equals(p.getFileName().toString())) {
            pomDirty = true;
            wake = true;
          }
          continue;
        }
        if (!scanFound.isEmpty()) scanFound.remove(p); // sistemul l-a anunțat totuși: nu e o modificare "scăpată"
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
          // Nu ștergem nimic pe loc: la o salvare atomică (temp + redenumire) fișierul dispare o clipă și reapare.
          pendingDeletes.put(p, System.nanoTime() + DELETE_GRACE_MS * 1_000_000L);
          wake = true;
          continue;
        }
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
          try {
            registerTree(p);
          } catch (IOException | ClosedWatchServiceException e) {
            // scanarea periodică acoperă
          }
          newDirs.add(p); // fișierele apărute în el înainte de înregistrare: parcurgem doar acest subarbore
        } else if (isEditorTemp(p)) {
          continue;
        } else {
          dirtyFiles.add(p); // creat sau modificat: verificăm DOAR fișierul acesta
        }
        pendingDeletes.remove(p); // a reapărut
        wake = true;
      }
    }
    if (!key.reset()) {
      watchDirs.remove(key);
      needFullScan = true;
      wake = true;
    }
    return wake;
  }

  // -----------------------------------------------------------------------------------
  // Scanner
  // -----------------------------------------------------------------------------------
  /** Programează un tick peste {@code delayMs}; dacă e deja programat unul mai devreme, nu mai face nimic. */
  private void requestTick(long delayMs) {
    if (!deployRunning) return;
    final long due = System.nanoTime() + delayMs * 1_000_000L;
    long cur;
    do {
      cur = tickDue.get();
      if (cur <= due) return;
    } while (!tickDue.compareAndSet(cur, due));
    try {
      scheduler.schedule(
          () -> {
            tickDue.compareAndSet(due, Long.MAX_VALUE);
            tick(false);
          },
          delayMs,
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      tickDue.compareAndSet(due, Long.MAX_VALUE);
    }
  }

  /**
   * Scanarea completă de siguranță. Cu evenimente de sistem care se dovedesc de încredere, se rărește singură
   * (1,5 s -> 3 -> 6 -> 12 -> 15 s): în repaus nu mai parcurgem tot proiectul de 40 de ori pe minut. Dacă găsește
   * vreodată o modificare pe care sistemul n-a anunțat-o (disc de rețea...), rămâne la 1,5 s, ca înainte.
   */
  private void safetyTick(int gen) {
    if (!deployRunning || gen != deployGen) return;
    if (nativeEvents && !eventsMissed && !scanFound.isEmpty()) {
      long limit = System.nanoTime() - 1_000_000_000L;
      if (scanFound.values().removeIf(t -> t - limit < 0)) {
        eventsMissed = true;
        log("[INFO] O modificare nu a fost anunțată de sistemul de fișiere (disc de rețea?) -> verific tot proiectul la "
            + POLL_SAFETY_MS + " ms.");
      }
    }
    tick(true);
    long d;
    if (!nativeEvents) {
      d = POLL_ONLY_MS;
    } else if (!eventsProven || eventsMissed) {
      d = safetyDelay = POLL_SAFETY_MS;
    } else {
      d = safetyDelay;
      safetyDelay = Math.min(safetyDelay * 2, POLL_SAFETY_MAX_MS);
    }
    try {
      if (deployRunning && gen == deployGen) watchTask = scheduler.schedule(() -> safetyTick(gen), d, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {
      // programul se închide
    }
  }

  private List<SyncRoot> syncRoots() {
    List<SyncRoot> roots = new ArrayList<>();
    if (webRoot != null) roots.add(webRoot);
    roots.addAll(resRoots);
    return roots;
  }

  private static <T> List<T> drain(Set<T> set) {
    List<T> out = new ArrayList<>();
    for (Iterator<T> it = set.iterator(); it.hasNext(); ) {
      out.add(it.next());
      it.remove();
    }
    return out;
  }

  /**
   * Procesează ce s-a adunat. Rulează pe thread-ul de evenimente sau pe cel "scan", niciodată în paralel (tickLock).
   * {@code periodic}=true vine din scanarea de siguranță; altfel procesăm doar ce au semnalat evenimentele.
   * Nu aruncă niciodată.
   */
  private void tick(boolean periodic) {
    if (!deployRunning) return;
    synchronized (tickLock) {
      if (!deployRunning) return;
      try {
        boolean full = periodic || needFullScan;
        needFullScan = false;
        List<Path> dirty = drain(dirtyFiles);
        List<Path> dirs = drain(newDirs);
        if (full) {
          Set<Path> found = periodic && nativeEvents && !eventsMissed ? new HashSet<>() : null;
          scanJava(ScanMode.LIVE, found);
          scanCopy(webRoot, ScanMode.LIVE, "[WEB]", found);
          for (SyncRoot r : resRoots) scanCopy(r, ScanMode.LIVE, "[RES]", found);
          if (found != null) {
            dirty.forEach(found::remove); // anunțate deja de evenimente, doar că scanarea a ajuns prima la ele
            if (!found.isEmpty()) {
              long t = System.nanoTime();
              for (Path p : found) scanFound.put(p, t);
              safetyDelay = POLL_SAFETY_MS;
            }
          }
          dirty.clear();
          dirs.clear();
          emptyDefer.clear();
        }
        long now = System.nanoTime();
        long next = Long.MAX_VALUE;
        // 1. ștergeri a căror perioadă de grație a trecut
        List<Path> gone = null;
        for (Iterator<Map.Entry<Path, Long>> it = pendingDeletes.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Path, Long> e = it.next();
          if (e.getValue() - now <= 0) {
            it.remove();
            if (gone == null) gone = new ArrayList<>();
            gone.add(e.getKey());
          } else {
            next = Math.min(next, e.getValue());
          }
        }
        if (gone != null) processGone(gone, dirty);
        // 2. foldere noi: doar subarborele lor
        for (Path d : dirs) collectTree(d, dirty);
        // 3. fișiere rămase goale după perioada de grație: chiar sunt goale
        Set<Path> acceptEmpty = null;
        for (Iterator<Map.Entry<Path, Long>> it = emptyDefer.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Path, Long> e = it.next();
          if (e.getValue() - now <= 0) {
            it.remove();
            if (acceptEmpty == null) acceptEmpty = new HashSet<>();
            acceptEmpty.add(e.getKey());
            dirty.add(e.getKey());
          }
        }
        // 4. fișierele semnalate
        if (!dirty.isEmpty()) processDirty(dirty, acceptEmpty, now);
        for (long d : emptyDefer.values()) next = Math.min(next, d);

        if (full || pomDirty) {
          pomDirty = false;
          checkPom();
        }
        flushJava();
        if (!pending.isEmpty()) requestTick(JAVA_DEBOUNCE_MS);
        if (next != Long.MAX_VALUE) requestTick(Math.max(1, (next - System.nanoTime()) / 1_000_000L + 1));
      } catch (Throwable t) {
        log("[EROARE] Watcher: " + t);
      }
    }
  }

  private static boolean underSkipped(Path rel) {
    for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return true;
    return false;
  }

  /** Toate fișierele dintr-un folder (nou apărut), fără folderele ignorate. */
  private static void collectTree(Path dir, List<Path> out) {
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
              boolean skip = !d.equals(dir) && SKIP_DIRS.contains(d.getFileName().toString());
              return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
              out.add(f);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // scanarea periodică acoperă
    }
  }

  private void removeDest(SyncRoot r, Path p) {
    Path rel = r.src.relativize(p);
    if (r.isProtected(rel)) return;
    String tag = r == webRoot ? "[WEB]" : "[RES]";
    try {
      if (Files.deleteIfExists(r.destOf(p))) log(tag + " Șters din Tomcat -> " + slash(rel));
    } catch (IOException e) {
      log("[EROARE] Nu pot șterge " + slash(rel) + ": " + e.getMessage());
    }
  }

  /**
   * Căi dispărute (după perioada de grație). Dacă între timp au reapărut, le tratăm ca modificate; altfel ștergem din
   * Tomcat exact ce le corespunde - fișierul, sau tot ce urmăream sub folderul dispărut - fără să scanăm proiectul.
   */
  private void processGone(List<Path> gone, List<Path> dirty) {
    List<SyncRoot> roots = syncRoots();
    for (Path g : gone) {
      if (Files.exists(g, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isDirectory(g, LinkOption.NOFOLLOW_LINKS)) collectTree(g, dirty);
        else if (!isEditorTemp(g)) dirty.add(g);
        continue;
      }
      boolean direct = false;
      if (javaMtimes.remove(g) != null) {
        pending.remove(g);
        deleteClasses(g);
        direct = true;
      }
      for (SyncRoot r : roots) {
        if (r.mtimes.remove(g) != null) {
          removeDest(r, g);
          direct = true;
        }
      }
      if (direct || isEditorTemp(g)) continue;
      // nu era un fișier urmărit: poate un folder
      if (g.startsWith(javaSrc)) {
        for (Path j : new ArrayList<>(javaMtimes.keySet())) {
          if (j.startsWith(g)) {
            javaMtimes.remove(j);
            pending.remove(j);
            deleteClasses(j);
          }
        }
      }
      for (SyncRoot r : roots) {
        if (!g.startsWith(r.src)) continue;
        for (Iterator<Path> it = r.mtimes.keySet().iterator(); it.hasNext(); ) {
          Path p = it.next();
          if (p.startsWith(g)) {
            it.remove();
            removeDest(r, p);
          }
        }
      }
    }
  }

  /** Procesare țintită: verificăm doar fișierele semnalate, fără să parcurgem tot arborele. */
  private void processDirty(List<Path> dirty, Set<Path> acceptEmpty, long nowNanos) throws IOException {
    Map<SyncRoot, List<Path>> toCopy = new LinkedHashMap<>();
    long now = System.currentTimeMillis();
    List<SyncRoot> roots = syncRoots();
    for (Path p : dirty) {
      BasicFileAttributes a;
      try {
        a = Files.readAttributes(p, BasicFileAttributes.class);
      } catch (IOException e) {
        continue; // dispărut: îl tratează evenimentul de ștergere
      }
      if (!a.isRegularFile()) continue;
      Stamp s = Stamp.of(a);
      boolean emptyOk = acceptEmpty != null && acceptEmpty.contains(p);
      if (p.startsWith(javaSrc)) {
        if (p.toString().endsWith(".java") && !underSkipped(javaSrc.relativize(p))) {
          if (deferEmpty(p, s, javaMtimes.get(p), emptyOk, nowNanos)) continue;
          // evenimentul a sosit după salvare: compilăm imediat, fără debounce
          if (trackJava(p, s, now - JAVA_DEBOUNCE_MS, true)) eventsProven = true;
        }
        continue;
      }
      for (SyncRoot r : roots) {
        if (!p.startsWith(r.src)) continue;
        Path rel = r.src.relativize(p);
        if (underSkipped(rel) || !r.accepts(rel)) continue;
        if (deferEmpty(p, s, r.mtimes.get(p), emptyOk, nowNanos)) continue;
        if (registerChange(r, p, s, ScanMode.LIVE)) {
          eventsProven = true;
          toCopy.computeIfAbsent(r, k -> new ArrayList<>()).add(p);
        }
      }
    }
    for (Map.Entry<SyncRoot, List<Path>> e : toCopy.entrySet()) {
      copyChanged(e.getKey(), e.getValue(), e.getKey() == webRoot ? "[WEB]" : "[RES]", true);
    }
  }

  /**
   * Un fișier care avea conținut și acum are 0 octeți e, aproape sigur, o salvare "pe loc" prinsă între trunchiere și
   * scriere. Nu îl trimitem gol în Tomcat: așteptăm evenimentul următor (sau {@link #EMPTY_GRACE_MS}, dacă chiar e gol).
   */
  private boolean deferEmpty(Path p, Stamp s, Stamp old, boolean emptyOk, long nowNanos) {
    if (s.size() == 0 && old != null && old.size() > 0 && !emptyOk) {
      emptyDefer.putIfAbsent(p, nowNanos + EMPTY_GRACE_MS * 1_000_000L);
      return true;
    }
    if (!emptyDefer.isEmpty()) emptyDefer.remove(p);
    return false;
  }

  /** @return true dacă fișierul e nou sau modificat față de ultima verificare */
  private boolean trackJava(Path f, Stamp s, long now, boolean live) {
    if (s.equals(javaMtimes.put(f, s))) return false;
    if (live) pending.put(f, now); // repornește debounce-ul
    return true;
  }

  /**
   * @param found dacă nu e null: primește fișierele deja urmărite pe care scanarea le-a găsit modificate (pentru a
   *     verifica dacă evenimentele de sistem le-au anunțat și ele)
   */
  private void scanJava(ScanMode mode, Set<Path> found) throws IOException {
    if (!Files.isDirectory(javaSrc)) return;
    final long now = System.currentTimeMillis();
    final Set<Path> seen = new HashSet<>();
    final boolean[] failed = {false};
    Files.walkFileTree(
        javaSrc,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
            boolean skip = !d.equals(javaSrc) && SKIP_DIRS.contains(d.getFileName().toString());
            return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
            if (f.toString().endsWith(".java")) {
              seen.add(f);
              boolean known = found != null && javaMtimes.containsKey(f);
              if (trackJava(f, Stamp.of(a), now, mode == ScanMode.LIVE) && known) found.add(f);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path f, IOException e) {
            failed[0] = true;
            return FileVisitResult.CONTINUE;
          }
        });

    if (!failed[0]) { // fișiere .java dispărute -> după perioada de grație ștergem și .class-urile
      for (Path p : javaMtimes.keySet()) {
        if (seen.contains(p)) continue;
        if (mode == ScanMode.LIVE) {
          pendingDeletes.putIfAbsent(p, System.nanoTime() + DELETE_GRACE_MS * 1_000_000L);
        } else {
          javaMtimes.remove(p);
          pending.remove(p);
        }
      }
    }
  }

  /** Trimite la compilare fișierele .java care s-au "liniștit" (au trecut JAVA_DEBOUNCE_MS fără modificări). */
  private void flushJava() {
    if (pending.isEmpty()) return;
    long now = System.currentTimeMillis();
    List<Path> ready = new ArrayList<>();
    pending.forEach((p, t) -> { if (now - t >= JAVA_DEBOUNCE_MS) ready.add(p); });
    if (ready.isEmpty()) return;
    ready.forEach(pending::remove);
    compileExec.submit(() -> compile(ready)); // thread dedicat; scanner-ul merge mai departe
  }

  private void deleteClasses(Path javaFile) {
    try {
      String rel = javaSrc.relativize(javaFile).toString();
      Path cls = classesDir.resolve(rel.substring(0, rel.length() - ".java".length()) + ".class");
      String fileName = cls.getFileName().toString();
      String base = fileName.substring(0, fileName.length() - ".class".length());
      Files.deleteIfExists(cls);
      compiledSrc.remove(javaFile);
      classGen++;
      JavacSession js = javac;
      if (js != null) js.dropPool(); // clasa dispare: simbolurile păstrate de compilator nu mai sunt valide
      Path dir = cls.getParent();
      if (dir != null && Files.isDirectory(dir)) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, base + "$*.class")) {
          for (Path n : ds) Files.deleteIfExists(n);
        }
      }
      log("[JAVA] Șters din Tomcat -> " + base + ".class");
    } catch (IOException e) {
      log("[EROARE] Nu pot șterge .class: " + e.getMessage());
    }
  }

  /** true dacă fișierul e nou/modificat față de ultima verificare (și, la FRESH, lipsește din Tomcat). */
  private static boolean registerChange(SyncRoot root, Path f, Stamp s, ScanMode mode) {
    Stamp old = root.mtimes.put(f, s);
    if (s.equals(old)) return false;
    return mode != ScanMode.FRESH || Files.notExists(root.destOf(f));
  }

  private void scanCopy(SyncRoot root, ScanMode mode, String tag, Set<Path> found) throws IOException {
    if (root == null || !Files.isDirectory(root.src)) return;
    final Set<Path> seen = new HashSet<>();
    final List<Path> changed = new ArrayList<>();
    final boolean[] failed = {false};
    Files.walkFileTree(
        root.src,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
            boolean skip = !d.equals(root.src) && SKIP_DIRS.contains(d.getFileName().toString());
            return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
            if (!root.accepts(root.src.relativize(f))) return FileVisitResult.CONTINUE;
            seen.add(f);
            boolean known = found != null && root.mtimes.containsKey(f);
            if (registerChange(root, f, Stamp.of(a), mode)) {
              changed.add(f);
              if (known) found.add(f);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path f, IOException e) {
            failed[0] = true;
            return FileVisitResult.CONTINUE;
          }
        });

    if (!failed[0]) { // fișiere dispărute din sursă -> după perioada de grație le ștergem din Tomcat
      for (Path p : root.mtimes.keySet()) {
        if (!seen.contains(p)) pendingDeletes.putIfAbsent(p, System.nanoTime() + DELETE_GRACE_MS * 1_000_000L);
      }
    }
    if (changed.isEmpty()) {
      if (mode == ScanMode.RESUME) log(tag + " Reluare: toate cele " + seen.size() + " fișiere sunt deja la zi.");
      return;
    }
    int n = copyChanged(root, changed, tag, mode != ScanMode.FRESH);
    if (mode == ScanMode.FRESH) log(tag + " " + changed.size() + " fișiere sincronizate.");
    if (mode == ScanMode.RESUME) {
      log(tag + " Reluare: " + n + " fișiere diferite sincronizate, " + (seen.size() - n) + " deja la zi.");
    }
  }

  /** Copiază fișierele pe loc; puține -> direct pe thread-ul curent (fără predare), multe -> în paralel. */
  private int copyChanged(SyncRoot root, List<Path> files, String tag, boolean verbose) {
    int n = 0;
    if (files.size() <= 2) {
      for (Path p : files) if (copyOne(root, p, tag, verbose)) n++;
      return n;
    }
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    for (Path p : files) futures.add(CompletableFuture.supplyAsync(() -> copyOne(root, p, tag, verbose), copyPool));
    for (CompletableFuture<Boolean> f : futures) if (f.join()) n++;
    return n;
  }

  /** @return true dacă fișierul din Tomcat a fost efectiv modificat */
  private boolean copyOne(SyncRoot root, Path file, String tag, boolean verbose) {
    Path rel = root.src.relativize(file);
    if (root.isProtected(rel)) return false;
    try {
      Path dest = root.dest.resolve(rel);
      PomInfo p = pom;
      boolean changed;
      if (root.filtering && p != null && Files.size(file) <= MAX_IN_MEMORY) {
        changed = writeInPlace(filterContent(file, Files.readAllBytes(file), p), dest);
      } else {
        changed = syncInPlace(file, dest);
      }
      if (verbose && changed) log(tag + " Modificat pe loc -> " + slash(rel));
      return changed;
    } catch (IOException e) {
      log("[EROARE] Nu pot copia " + slash(rel) + ": " + e.getMessage());
      return false;
    }
  }

  private static String slash(Path p) {
    return p.toString().replace('\\', '/');
  }

  /** Dacă pom.xml s-a schimbat, regenerăm META-INF și, la nevoie, dependențele. */
  private void checkPom() throws IOException {
    Path pomFile = projectDir.resolve("pom.xml");
    if (!Files.isRegularFile(pomFile)) return;
    long m = fileStamp(pomFile);
    if (m == pomMtime) return;
    pomMtime = m;
    try {
      PomInfo info = PomInfo.read(projectDir);
      pom = info;
      pomRelease = info.release;
      JavacSession js = javac;
      if (js != null) {
        js.procNone = !needsAnnotationProcessing(pomFile);
        js.sourceCharset = info.charset();
      }
      String warV = info.warPluginVersion != null ? info.warPluginVersion : warVersion;
      writeMetaInf(target, info, warV, mavenVersion);
      log("[POM] pom.xml modificat -> META-INF regenerat.");
      for (SyncRoot r : resRoots) {
        if (r.filtering) r.mtimes.clear(); // valorile din pom s-ar putea să fi schimbat: refiltrăm (scrie doar ce diferă)
      }
      String fp = depsFingerprint(pomFile);
      if (!fp.equals(depsFingerprint)) {
        depsFingerprint = fp;
        compileExec.submit(this::refreshDependencies);
      }
    } catch (Exception e) {
      log("[EROARE] pom.xml: " + e);
    }
  }

  // ===================================================================================
  // Browser
  // ===================================================================================
  /** Calea din URL pentru un nume de context: ROOT -> "/", "app" -> "/app/", "a#b" -> "/a/b/", "a##2" -> "/a/". */
  static String contextUrlPath(String ctx) {
    String base = ctx.split("##", 2)[0];
    if (base.equals("ROOT")) return "/";
    return "/" + base.replace('#', '/') + "/";
  }

  private void openInBrowser() {
    final Path home = tomcatHome;
    if (home == null) return;
    String ctx = contextName();
    if (ctx == null) {
      JOptionPane.showMessageDialog(this, "Numele contextului e invalid.", "Context invalid", JOptionPane.WARNING_MESSAGE);
      return;
    }
    final int port = httpPort(home);
    final String url = "http://localhost:" + port + contextUrlPath(ctx);
    bg.submit(
        () -> {
          if (!portOpen(port)) {
            log("[AVERTISMENT] Tomcat nu răspunde pe portul " + port + ". Pornește-l; deschid oricum pagina.");
          }
          try {
            browse(url);
            log("[OK] Am deschis în browser: " + url);
          } catch (Exception e) {
            log("[EROARE] Nu pot deschide browserul: " + e.getMessage() + " (adresa: " + url + ")");
          }
        });
  }

  static void browse(String url) throws Exception {
    if (java.awt.Desktop.isDesktopSupported()
        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
      java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
      return;
    }
    boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    List<String> cmd =
        WINDOWS
            ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
            : mac ? List.of("open", url) : List.of("xdg-open", url);
    new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  }

  // ===================================================================================
  // Compilare Java (în program, cu javax.tools - fără proces extern, identic pe Windows/Linux)
  // ===================================================================================
  /** Peste atâtea fișiere într-o cerere (compilare în masă) nu mai ținem evidența conținutului. */
  static final int TRACK_MAX = 16;

  /** Ce am compilat ultima dată dintr-un fișier: amprenta conținutului și "generația" claselor din Tomcat. */
  private record Compiled(byte[] hash, int gen) {}

  private final Map<Path, Compiled> compiledSrc = new ConcurrentHashMap<>();
  /** Crește de fiecare dată când o compilare schimbă efectiv vreo clasă din Tomcat. */
  private volatile int classGen;
  /** 1 thread, prioritate mică: pregătește contextul de rezervă al compilatorului și încălzește JIT-ul. */
  private final ExecutorService warmExec = Executors.newSingleThreadExecutor(daemon("warm"));
  private volatile List<Path> cpCache;
  private volatile String cpCacheKey;

  private void compile(List<Path> requested) {
    if (!deployRunning && !preparing) return;
    final JavacSession s = javac;
    if (s == null) return; // sesiune închisă între timp (deploy oprit)
    // Citim fișierele o singură dată: (1) dacă e exact conținutul deja compilat (eveniment dublu, "touch") nu mai
    // compilăm nimic; (2) javac primește exact acești octeți, nu mai citește el de pe disc.
    final boolean track = requested.size() <= TRACK_MAX;
    final List<Path> files = new ArrayList<>(requested.size());
    final Map<Path, byte[]> content = track ? new HashMap<>() : null;
    final Map<Path, byte[]> hashes = track ? new HashMap<>() : null;
    final Map<Path, Long> before = new HashMap<>();
    final int gen = classGen;
    for (Path f : requested) {
      long st = stampOf(f);
      if (st < 0) continue; // poate a fost șters între timp
      if (track) {
        try {
          byte[] b = Files.readAllBytes(f);
          if (b.length == 0 && compiledSrc.containsKey(f)) continue; // prins la jumătatea salvării: vine alt eveniment
          byte[] h = digest(b);
          Compiled c = compiledSrc.get(f);
          if (c != null && c.gen() == gen && Arrays.equals(c.hash(), h)) continue;
          content.put(f, b);
          hashes.put(f, h);
        } catch (IOException e) {
          // îl citește javac și raportează el eroarea
        }
      }
      before.put(f, st);
      files.add(f);
    }
    if (files.isEmpty()) return;
    String names =
        files.stream().limit(4).map(f -> f.getFileName().toString()).collect(Collectors.joining(", "))
            + (files.size() > 4 ? ", ... (" + files.size() + " fișiere)" : "");
    log("[JAVA] Compilez: " + names);
    long t0 = System.nanoTime();
    try {
      List<Path> cp = classpathEntries();
      int release = effectiveRelease();
      JavacSession.Result r = s.compile(files, content, javaSrc, classesDir, cp, release);
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (r.ok()) {
        if (r.written > 0 || !r.removed.isEmpty()) classGen = gen + 1;
        if (track) {
          int now = classGen;
          for (Path f : files) {
            byte[] h = hashes.get(f);
            if (h != null) compiledSrc.put(f, new Compiled(h, now));
          }
        }
        for (String n : r.removed) log("[JAVA] Șters din Tomcat -> " + n + ".class (nu mai există în sursă)");
        if (r.written == 0 && r.unchanged > 0) {
          log("[OK] Compilat în " + ms + " ms: clasele sunt identice cu cele din Tomcat, n-am atins nimic.");
        } else {
          log("[OK] Compilat în " + ms + " ms și trimis direct în Tomcat"
              + (r.unchanged > 0 ? " (" + r.unchanged + " clase identice, neatinse)." : "."));
        }
        compileOk();
      } else if (files.stream().anyMatch(f -> stampOf(f) != before.get(f))) {
        long now = System.currentTimeMillis(); // fișierul se salva încă în timpul compilării -> reluăm, fără eroare falsă
        for (Path f : files) pending.put(f, now);
        requestTick(JAVA_DEBOUNCE_MS);
      } else {
        log("[EROARE Java] Compilarea a eșuat:");
        r.errors.forEach(e -> log("   " + e));
        compileFailed(names, r.errors);
      }
      if (!s.hasSpare()) scheduleWarm(() -> s.refill(javaSrc, classesDir, cp, release));
    } catch (Exception e) {
      log("[EROARE] " + e.getMessage());
    }
  }

  private void scheduleWarm(Runnable r) {
    try {
      warmExec.execute(
          () -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            r.run();
          });
    } catch (RejectedExecutionException ignored) {
      // programul se închide
    }
  }

  static byte[] digest(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Versiunea țintă pentru compilare: cea din pom.xml. Dacă pom-ul nu spune nimic, clasele sunt făcute pentru JDK-ul ales
   * pentru Tomcat (când e mai vechi decât cel cu care rulează programul) - altfel Tomcat nu le-ar putea încărca.
   */
  private int effectiveRelease() {
    int r = pomRelease;
    int jf = jdkFeature;
    if (r == 0 && jf >= 8 && jf < Runtime.version().feature()) return jf;
    return r;
  }

  static long dirStamp(Path dir) {
    try {
      return Files.getLastModifiedTime(dir).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  /**
   * Classpath: WEB-INF/classes + WEB-INF/lib/*.jar + lib/*.jar din Tomcat (servlet-api, jsp-api...). Lista e reținută
   * și recitită de pe disc doar când se schimbă conținutul celor două foldere (ora lor de modificare).
   */
  private List<Path> classpathEntries() {
    Path lib = target.resolve("WEB-INF").resolve("lib");
    Path tomcatLib = tomcatHome.resolve("lib");
    String key = classesDir + "|" + dirStamp(lib) + "|" + tomcatLib + "|" + dirStamp(tomcatLib);
    List<Path> cached = cpCache;
    if (cached != null && key.equals(cpCacheKey)) return cached;
    List<Path> cp = new ArrayList<>();
    cp.add(classesDir);
    addJars(cp, lib);
    addJars(cp, tomcatLib);
    cached = List.copyOf(cp);
    cpCache = cached;
    cpCacheKey = key;
    return cached;
  }

  static void addJars(List<Path> out, Path dir) {
    if (!Files.isDirectory(dir)) return;
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(out::add);
    } catch (IOException ignored) {
      // nimic
    }
  }

  private static long stampOf(Path f) {
    try {
      return fileStamp(f);
    } catch (IOException e) {
      return -1;
    }
  }

  /**
   * Rulat în fundal după pregătirea deploy-ului: pregătește contextul de rezervă al compilatorului (indexează și
   * jar-urile din classpath), apoi încălzește JIT-ul lui javac. Nu scrie nimic în Tomcat și cedează locul salvărilor.
   */
  private void warmClasspath() {
    JavacSession s = javac;
    if (s == null) return;
    try {
      long t0 = System.nanoTime();
      List<Path> cp = classpathEntries();
      int release = effectiveRelease();
      List<Path> all = new ArrayList<>(javaMtimes.keySet());
      all.removeIf(f -> f.getFileName().toString().equals("package-info.java") || f.getFileName().toString().equals("module-info.java"));
      List<Path> sample = new ArrayList<>();
      for (int i = 0, step = Math.max(1, all.size() / 12); i < all.size() && sample.size() < 12; i += step) sample.add(all.get(i));
      s.seedLibraries(javaSrc, classesDir, 400);
      s.warmUp(javaSrc, classesDir, cp, release, 8, sample);
      log("[JAVA] Compilator pregătit în " + (System.nanoTime() - t0) / 1_000_000 + " ms - prima salvare e rapidă.");
      s.warmUp(javaSrc, classesDir, cp, release, WARM_ROUNDS, sample);
    } catch (Throwable ignored) {
      // opțional: prima compilare reală va plăti costul
    }
  }

  /**
   * -proc:none oprește căutarea de procesatoare de adnotări (câștig la fiecare compilare), dar strică proiectele cu
   * Lombok, MapStruct etc. Îl folosim doar dacă pom.xml nu menționează nimic cunoscut. Forțare: -Dtd.proc=true.
   */
  static boolean needsAnnotationProcessing(Path pomFile) {
    if (Boolean.getBoolean("td.proc")) return true;
    if (Boolean.getBoolean("td.noproc")) return false; // pentru teste: proiect fără procesatoare de adnotări
    try {
      String t = Files.readString(pomFile, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      for (String k :
          List.of("lombok", "mapstruct", "annotationprocessor", "dagger", "auto-service", "auto-value", "immutables",
              "jpamodelgen", "querydsl", "micronaut", "-processor")) {
        if (t.contains(k)) return true;
      }
      return false;
    } catch (IOException e) {
      return true; // nu știm -> comportament sigur
    }
  }


  /**
   * Amprenta "interfeței" unui .class: tot ce văd alte clase (membri, semnături, constante, adnotări), fără corpurile
   * metodelor. Două versiuni cu aceeași amprentă nu pot strica compilarea clasele dependente.
   * Membrii sintetici (lambda$..., access$...) și clasele anonime/locale sunt ignorate; orice neclar -> amprentă
   * diferită (mai sigur: costă doar o compilare mai lentă).
   */
  static final class Abi {
    private final Object[] cp;
    private final int[] tag;

    private Abi(Object[] cp, int[] tag) {
      this.cp = cp;
      this.tag = tag;
    }

    /** Adaugă în {@code out} numele interne ("a/b/C$D") ale tuturor claselor referite de un .class. */
    static void classRefs(byte[] b, Set<String> out) {
      try {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) return;
        in.readInt();
        int n = in.readUnsignedShort();
        String[] utf = new String[n];
        int[] cls = new int[n];
        int nc = 0;
        for (int i = 1; i < n; i++) {
          int t = in.readUnsignedByte();
          switch (t) {
            case 1 -> utf[i] = in.readUTF();
            case 3, 4 -> in.readInt();
            case 5, 6 -> {
              in.readLong();
              i++;
            }
            case 7 -> cls[nc++] = in.readUnsignedShort();
            case 8, 16, 19, 20 -> in.readUnsignedShort();
            case 9, 10, 11, 12, 17, 18 -> in.readInt();
            case 15 -> {
              in.readUnsignedByte();
              in.readUnsignedShort();
            }
            default -> {
              return;
            }
          }
        }
        for (int i = 0; i < nc; i++) {
          String name = cls[i] > 0 && cls[i] < n ? utf[cls[i]] : null;
          if (name != null) out.add(name);
        }
      } catch (IOException | RuntimeException e) {
        // fișier neașteptat: fără referințe
      }
    }

    static boolean isLocalName(String className) {
      int i = Math.max(className.lastIndexOf('$'), className.lastIndexOf('.'));
      return i >= 0 && i + 1 < className.length() && className.charAt(i) == '$' && Character.isDigit(className.charAt(i + 1));
    }

    /** @return amprenta sau null dacă fișierul nu poate fi citit */
    static String of(byte[] b) {
      try {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) return null;
        in.readUnsignedShort();
        int major = in.readUnsignedShort();
        int n = in.readUnsignedShort();
        Object[] cp = new Object[n];
        int[] tag = new int[n];
        for (int i = 1; i < n; i++) {
          int t = in.readUnsignedByte();
          tag[i] = t;
          switch (t) {
            case 1 -> cp[i] = in.readUTF();
            case 3 -> cp[i] = "I" + in.readInt();
            case 4 -> cp[i] = "F" + in.readInt();
            case 5 -> {
              cp[i] = "J" + in.readLong();
              i++;
            }
            case 6 -> {
              cp[i] = "D" + in.readLong();
              i++;
            }
            case 7, 8, 16, 19, 20 -> cp[i] = new int[] {in.readUnsignedShort()};
            case 9, 10, 11, 12, 17, 18 -> cp[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
            case 15 -> cp[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
            default -> {
              return null;
            }
          }
        }
        Abi a = new Abi(cp, tag);
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('|').append(in.readUnsignedShort() & ~0x20).append('|'); // access (fără ACC_SUPER)
        sb.append(a.res(in.readUnsignedShort())).append('|').append(a.res(in.readUnsignedShort())).append('|');
        int ni = in.readUnsignedShort();
        for (int i = 0; i < ni; i++) sb.append(a.res(in.readUnsignedShort())).append(',');
        sb.append('|');
        for (int pass = 0; pass < 2; pass++) { // 0 = câmpuri, 1 = metode
          int cnt = in.readUnsignedShort();
          for (int i = 0; i < cnt; i++) {
            int acc = in.readUnsignedShort();
            String name = a.res(in.readUnsignedShort());
            String desc = a.res(in.readUnsignedShort());
            boolean skip = (acc & 0x1000) != 0; // ACC_SYNTHETIC
            StringBuilder m = new StringBuilder();
            m.append(pass).append(':').append(acc).append(':').append(name).append(':').append(desc).append('{');
            a.attrs(in, m, true);
            m.append('}');
            if (!skip) sb.append(m).append(';');
          }
        }
        sb.append('#');
        a.attrs(in, sb, false);
        return sb.toString();
      } catch (Exception e) {
        return null;
      }
    }

    private String res(int i) {
      if (i <= 0 || i >= cp.length) return "";
      Object o = cp[i];
      if (o instanceof String str) return str;
      if (o instanceof int[] r) {
        switch (tag[i]) {
          case 7, 8, 16, 19, 20:
            return res(r[0]);
          case 9, 10, 11, 12, 17, 18:
            return res(r[0]) + "." + res(r[1]);
          case 15:
            return r[0] + ":" + res(r[1]);
          default:
            return "?";
        }
      }
      return String.valueOf(o);
    }

    private void attrs(java.io.DataInputStream in, StringBuilder sb, boolean member) throws IOException {
      int n = in.readUnsignedShort();
      for (int i = 0; i < n; i++) {
        String name = res(in.readUnsignedShort());
        int len = in.readInt();
        byte[] body = new byte[len];
        in.readFully(body);
        java.io.DataInputStream a = new java.io.DataInputStream(new java.io.ByteArrayInputStream(body));
        switch (name) {
          case "Code", "SourceFile", "BootstrapMethods", "EnclosingMethod", "LineNumberTable", "LocalVariableTable",
              "LocalVariableTypeTable", "SourceDebugExtension", "StackMapTable" -> {
            // corpuri/informații de depanare: nu fac parte din interfață
          }
          case "ConstantValue", "Signature", "NestHost" -> sb.append(name).append('=').append(res(a.readUnsignedShort())).append(';');
          case "Exceptions", "PermittedSubclasses" -> {
            int c = a.readUnsignedShort();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) sb.append(res(a.readUnsignedShort())).append(',');
            sb.append(';');
          }
          case "Record" -> {
            int c = a.readUnsignedShort();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) {
              sb.append(res(a.readUnsignedShort())).append(':').append(res(a.readUnsignedShort())).append('{');
              attrs(a, sb, true);
              sb.append('}');
            }
            sb.append(';');
          }
          case "NestMembers" -> {
            int c = a.readUnsignedShort();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) {
              String cn = res(a.readUnsignedShort()).replace('/', '.');
              if (!isLocalName(cn)) sb.append(cn).append(',');
            }
            sb.append(';');
          }
          case "InnerClasses" -> {
            int c = a.readUnsignedShort();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) {
              int inner = a.readUnsignedShort();
              int outer = a.readUnsignedShort();
              int iname = a.readUnsignedShort();
              int acc = a.readUnsignedShort();
              String in2 = res(inner).replace('/', '.');
              if (outer != 0 && iname != 0 && !isLocalName(in2)) {
                sb.append(in2).append('/').append(res(outer)).append('/').append(res(iname)).append('/').append(acc).append(',');
              }
            }
            sb.append(';');
          }
          case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> {
            int c = a.readUnsignedShort();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) annotation(a, sb);
            sb.append(';');
          }
          case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" -> {
            int np = a.readUnsignedByte();
            sb.append(name).append('=');
            for (int p = 0; p < np; p++) {
              int c = a.readUnsignedShort();
              sb.append('[');
              for (int k = 0; k < c; k++) annotation(a, sb);
              sb.append(']');
            }
            sb.append(';');
          }
          case "AnnotationDefault" -> {
            sb.append(name).append('=');
            elementValue(a, sb);
            sb.append(';');
          }
          case "MethodParameters" -> {
            int c = a.readUnsignedByte();
            sb.append(name).append('=');
            for (int k = 0; k < c; k++) sb.append(res(a.readUnsignedShort())).append('/').append(a.readUnsignedShort()).append(',');
            sb.append(';');
          }
          case "Deprecated" -> sb.append("Deprecated;");
          case "Synthetic" -> sb.append("Synthetic;");
          default -> { // necunoscut: octeții expliciți (poate da fals "schimbat", niciodată fals "la fel")
            sb.append(name).append('=');
            for (byte x : body) sb.append(Integer.toHexString(x & 0xff)).append('.');
            sb.append(';');
          }
        }
      }
    }

    private void annotation(java.io.DataInputStream a, StringBuilder sb) throws IOException {
      sb.append('@').append(res(a.readUnsignedShort())).append('(');
      int np = a.readUnsignedShort();
      for (int i = 0; i < np; i++) {
        sb.append(res(a.readUnsignedShort())).append('=');
        elementValue(a, sb);
        sb.append(',');
      }
      sb.append(')');
    }

    private void elementValue(java.io.DataInputStream a, StringBuilder sb) throws IOException {
      int t = a.readUnsignedByte();
      sb.append((char) t);
      switch ((char) t) {
        case 'e' -> sb.append(res(a.readUnsignedShort())).append('.').append(res(a.readUnsignedShort()));
        case '@' -> annotation(a, sb);
        case '[' -> {
          int c = a.readUnsignedShort();
          sb.append('[');
          for (int i = 0; i < c; i++) {
            elementValue(a, sb);
            sb.append(',');
          }
          sb.append(']');
        }
        default -> sb.append(res(a.readUnsignedShort())); // constantă, șir sau clasă
      }
    }
  }

  /**
   * Un context javac reutilizabil (JavacTaskPool, cel folosit de jshell): păstrează între compilări simbolurile deja
   * încărcate (JDK, jar-uri, clasele proiectului), deci o compilare nu mai pornește de la zero.
   */
  static final class TaskPool {
    private final Object pool;
    private final java.lang.reflect.Method getTask;
    private final Class<?> workerType;

    private TaskPool(Object pool, java.lang.reflect.Method getTask, Class<?> workerType) {
      this.pool = pool;
      this.getTask = getTask;
      this.workerType = workerType;
    }

    private static boolean exported() {
      return ModuleLayer.boot()
          .findModule("jdk.compiler")
          .map(m -> m.isExported("com.sun.tools.javac.api", TomcatDeploy.class.getModule()))
          .orElse(false);
    }

    /** true dacă JVM-ul permite accesul la pachetul intern al compilatorului (vezi {@link JavacAccess}). */
    static boolean available() {
      if (Boolean.getBoolean("td.nopool")) return false;
      try {
        return exported() || (JavacAccess.open() && exported());
      } catch (Throwable t) {
        return false;
      }
    }

    static TaskPool create() {
      if (!available()) return null;
      try {
        Class<?> pc = Class.forName("com.sun.tools.javac.api.JavacTaskPool");
        Class<?> wc = Class.forName("com.sun.tools.javac.api.JavacTaskPool$Worker");
        Object p = pc.getConstructor(int.class).newInstance(1);
        java.lang.reflect.Method m =
            pc.getMethod("getTask", java.io.Writer.class, JavaFileManager.class, javax.tools.DiagnosticListener.class,
                Iterable.class, Iterable.class, Iterable.class, wc);
        return new TaskPool(p, m, wc);
      } catch (Throwable t) {
        return null;
      }
    }

    void run(JavaFileManager fm, javax.tools.DiagnosticListener<JavaFileObject> dl, List<String> opts,
        Iterable<? extends JavaFileObject> units) throws Exception {
      Object worker =
          java.lang.reflect.Proxy.newProxyInstance(
              TomcatDeploy.class.getClassLoader(),
              new Class<?>[] {workerType},
              (proxy, method, args) -> {
                if (method.getName().equals("withTask")) {
                  com.sun.source.util.JavacTask t = (com.sun.source.util.JavacTask) args[0];
                  t.analyze();
                  t.generate();
                  return Boolean.TRUE;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "worker";
              });
      try {
        getTask.invoke(pool, null, fm, dl, opts, null, units, worker);
      } catch (java.lang.reflect.InvocationTargetException e) {
        Throwable c = e.getCause();
        throw c instanceof Exception ex ? ex : e;
      }
    }
  }

  /**
   * Deschide pachetul intern {@code com.sun.tools.javac.api} pentru acest program, în procesul curent - ca să nu mai fie
   * nevoie de relansarea cu {@code --add-exports} (un al doilea JVM și, în mod sursă, o a doua compilare a programului).
   * Folosește sun.misc.Unsafe doar prin reflexie; pe JDK-urile care nu mai permit asta întoarce false și programul revine
   * la relansare. Dezactivare: -Dtd.nohack=true.
   */
  static final class JavacAccess {
    private static boolean tried;
    private static boolean opened;

    static synchronized boolean open() {
      if (tried) return opened;
      tried = true;
      if (Boolean.getBoolean("td.nohack")) return false;
      try {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field tf = uc.getDeclaredField("theUnsafe");
        tf.setAccessible(true);
        Object unsafe = tf.get(null);
        java.lang.reflect.Field il = java.lang.invoke.MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = uc.getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(unsafe, il);
        long off = (Long) uc.getMethod("staticFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, il);
        java.lang.invoke.MethodHandles.Lookup trusted =
            (java.lang.invoke.MethodHandles.Lookup) uc.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, off);
        java.lang.invoke.MethodHandle mh =
            trusted.findVirtual(Module.class, "implAddExports",
                java.lang.invoke.MethodType.methodType(void.class, String.class, Module.class));
        Module compiler = ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
        mh.invoke(compiler, "com.sun.tools.javac.api", TomcatDeploy.class.getModule());
        opened = true;
      } catch (Throwable t) {
        opened = false;
      }
      return opened;
    }
  }

  /**
   * Compilatorul unei sesiuni de deploy.
   *
   * <p>File manager-ul rămâne deschis între compilări (jar-urile din classpath sunt indexate o singură dată), iar
   * compilările obișnuite trec prin contexte javac reutilizate:
   * <ul>
   *   <li><b>câte un context per fișier editat</b> (ultimele {@link #MAX_CTX}): a doua salvare a aceluiași fișier găsește
   *       deja încărcate toate clasele de care are nevoie. Într-un context se compilează mereu același fișier, deci
   *       javac nu ajunge să recompileze din sursă o clasă pe care o încărcase deja din .class;</li>
   *   <li><b>un context de rezervă</b>, pregătit în fundal doar cu clasele JDK-ului: prima salvare a unui fișier (sau
   *       prima după o schimbare de "interfață") nu mai pornește la rece.</li>
   * </ul>
   * Clasele generate rămân în memorie și sunt scrise în Tomcat pe loc, doar cele care diferă: o clasă neschimbată nu
   * mai e atinsă, deci HotswapAgent nu o mai reîncarcă degeaba.
   *
   * <p>Siguranță: dacă se schimbă "interfața" unei clase (sau apare/dispare una), toate contextele per fișier sunt
   * aruncate; erorile dintr-un context reutilizat sunt reconfirmate cu o compilare obișnuită, într-un context curat.
   */
  static final class JavacSession implements AutoCloseable {
    /** Câte contexte per fișier ținem calde (~5 MB fiecare). */
    static final int MAX_CTX = 6;
    /** Peste atâtea fișiere într-o compilare, clasele se scriu direct pe disc (fără să le ținem în memorie). */
    static final int MAX_IN_MEMORY_BATCH = 64;

    /** Rezultatul unei compilări. */
    static final class Result {
      final List<String> errors = new ArrayList<>();
      /** Clase scrise efectiv în Tomcat / clase generate identic cu cele existente (neatinse). */
      int written;
      int unchanged;
      /** Clase interne rămase de la o versiune mai veche a fișierului, șterse acum. */
      final List<String> removed = new ArrayList<>();

      boolean ok() {
        return errors.isEmpty();
      }
    }

    private JavaCompiler jc;
    private StandardJavaFileManager fm;
    /** Wrapper unic per file manager: contextele reutilizate păstrează prima instanță primită. */
    private OutFm ofm;
    private List<Path> lastClasspath;
    private Path lastSrc;
    private Path lastOut;
    private Charset lastCharset;
    private String lastOpts;
    /** 0 = --release N, 1 = -source N -target N, 2 = fără (JDK-uri fără ct.sym nu acceptă --release). */
    private int releaseMode;
    /** false = proiectul folosește procesatoare de adnotări (Lombok...) și nu putem trece -proc:none. */
    volatile boolean procNone = true;
    /** Encoding-ul surselor (project.build.sourceEncoding din pom). */
    volatile Charset sourceCharset = StandardCharsets.UTF_8;
    /** Câte compilări reale așteaptă: munca din fundal le cedează locul. */
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile boolean closed;
    private boolean poolOff = !TaskPool.available();
    private int poolFailures;
    /** Contexte per fișier (sau per grup de fișiere salvate împreună), cel mai recent folosit la urmă. */
    private final LinkedHashMap<Object, TaskPool> ctxs = new LinkedHashMap<>(16, 0.75f, true);
    /** Contextul de rezervă: încălzit doar cu surse sintetice, deci fără nicio clasă a proiectului. */
    private TaskPool spare;
    /** Fișiere pentru care reutilizarea contextului dă erori false (referințe reciproce): primesc mereu rezerva. */
    private final Set<Object> freshOnly = new HashSet<>();
    /** Amprenta "interfeței" claselor scrise de noi (ca să nu recitim .class-ul vechi la fiecare compilare). */
    private final Map<String, String> abiCache = new HashMap<>();
    /** Clasele generate ultima dată din fiecare fișier (pentru curățarea claselor interne dispărute). */
    private final Map<Path, Set<String>> outputsOf = new HashMap<>();
    /**
     * Clasele din librării (JDK, jar-uri) pe care le folosește proiectul, învățate din clasele compilate. Contextul de
     * rezervă le încarcă dinainte, în fundal, ca prima compilare a unui fișier să nu mai piardă timp cu ele.
     */
    private final Set<String> libTypes = new java.util.LinkedHashSet<>();
    /** Nume intern de clasă -> e din librării (true) sau din proiect / nefolosibilă (false). */
    private final Map<String, Boolean> libSeen = new HashMap<>();
    static final int MAX_LIB_TYPES = 400;
    /** Statistici (și pentru teste). */
    volatile int poolHits;
    volatile int poolSpare;
    volatile int poolCold;
    volatile int poolDrops;
    volatile int poolFallbacks;

    // ---------------------------------------------------------------------------------
    // Obiecte de fișier
    // ---------------------------------------------------------------------------------
    /** Sursă servită din memorie: javac compilează exact octeții pe care i-am citit noi (o singură citire de pe disc). */
    private static final class SnapshotSource extends javax.tools.ForwardingJavaFileObject<JavaFileObject> {
      private final byte[] data;
      private final Charset cs;
      private String text;

      SnapshotSource(JavaFileObject fo, byte[] data, Charset cs) {
        super(fo);
        this.data = data;
        this.cs = cs;
      }

      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        if (text == null) text = new String(data, cs);
        return text;
      }

      @Override
      public InputStream openInputStream() {
        return new java.io.ByteArrayInputStream(data);
      }

      @Override
      public java.io.Reader openReader(boolean ignoreEncodingErrors) {
        return new java.io.StringReader(getCharContent(true).toString());
      }
    }

    private static final class MemSource extends SimpleJavaFileObject {
      private final String code;

      MemSource(String name, String code) {
        super(java.net.URI.create("mem:///" + name + ".java"), Kind.SOURCE);
        this.code = code;
      }

      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return code;
      }
    }

    /** Un .class generat în memorie; ajunge în {@code sink} când javac îl închide (sink null = se aruncă). */
    private static final class MemClass extends SimpleJavaFileObject {
      private final String className;
      private final Map<String, byte[]> sink;

      MemClass(String className) {
        this(className, null);
      }

      MemClass(String className, Map<String, byte[]> sink) {
        super(java.net.URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
        this.className = className;
        this.sink = sink;
      }

      @Override
      public OutputStream openOutputStream() {
        if (sink == null) return OutputStream.nullOutputStream();
        return new ByteArrayOutputStream(4096) {
          @Override
          public void close() {
            sink.put(className, toByteArray());
          }
        };
      }

      @Override
      public boolean delete() {
        if (sink != null) sink.remove(className);
        return true;
      }
    }

    /** Trimite clasele generate în memorie cât timp {@code sink} e setat; altfel lasă javac să scrie direct pe disc. */
    private static final class OutFm extends ForwardingJavaFileManager<StandardJavaFileManager> {
      volatile Map<String, byte[]> sink;

      OutFm(StandardJavaFileManager fm) {
        super(fm);
      }

      @Override
      public JavaFileObject getJavaFileForOutput(
          Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        Map<String, byte[]> s = sink;
        if (s != null && kind == JavaFileObject.Kind.CLASS) return new MemClass(className, s);
        return super.getJavaFileForOutput(location, className, kind, sibling);
      }
    }

    // ---------------------------------------------------------------------------------
    // Contexte
    // ---------------------------------------------------------------------------------
    private void dropAll(boolean alsoSpare) {
      if (!ctxs.isEmpty()) poolDrops++;
      ctxs.clear();
      if (alsoSpare) spare = null;
    }

    /** A dispărut o clasă (sau s-a schimbat ceva pe dinafară): contextele per fișier nu mai sunt de încredere. */
    synchronized void dropPool() {
      dropAll(false);
      abiCache.clear();
      outputsOf.clear();
    }

    private void putCtx(Object key, TaskPool ctx) {
      ctxs.put(key, ctx);
      if (ctxs.size() > MAX_CTX) {
        Iterator<Object> it = ctxs.keySet().iterator();
        it.next();
        it.remove(); // cel mai demult folosit
      }
    }

    private static Object keyOf(List<Path> files) {
      if (files.size() == 1) return files.get(0);
      List<Path> k = new ArrayList<>(files);
      k.sort(null);
      return k;
    }

    // ---------------------------------------------------------------------------------
    // Compilare
    // ---------------------------------------------------------------------------------
    /**
     * @param content conținutul deja citit al fișierelor (opțional): javac compilează exact acești octeți
     */
    Result compile(List<Path> files, Map<Path, byte[]> content, Path javaSrc, Path classesDir, List<Path> classpath,
        int release) throws IOException {
      waiting.incrementAndGet(); // semnalează muncii din fundal să ne cedeze locul
      synchronized (this) {
        waiting.decrementAndGet();
        try {
          return attempt(files, content, javaSrc, classesDir, classpath, release);
        } catch (IllegalStateException e) {
          if (jc == null) throw e; // nu avem JDK
          invalidate(); // file manager într-o stare neașteptată: reîncercăm o dată cu unul nou
          return attempt(files, content, javaSrc, classesDir, classpath, release);
        }
      }
    }

    private void setup(Path javaSrc, Path classesDir, List<Path> classpath) throws IOException {
      if (jc == null) jc = ToolProvider.getSystemJavaCompiler();
      if (jc == null) {
        throw new IllegalStateException("Programul rulează pe un JRE. Pornește-l cu un JDK ca să poată compila Java.");
      }
      Charset cs = sourceCharset;
      if (fm != null && !cs.equals(lastCharset)) invalidate();
      if (fm == null) {
        fm = jc.getStandardFileManager(null, null, cs);
        ofm = new OutFm(fm);
        lastCharset = cs;
        lastClasspath = null;
        lastSrc = null;
        lastOut = null;
        dropAll(true);
      }
      // locațiile se schimbă doar când e nevoie: un setLocation resetează și închide jar-urile deschise
      if (!classpath.equals(lastClasspath)) {
        fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
        if (lastClasspath != null) dropAll(true); // jar-uri schimbate: simbolurile păstrate pot fi vechi
        lastClasspath = List.copyOf(classpath);
      }
      if (!javaSrc.equals(lastSrc)) {
        fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of(javaSrc));
        if (lastSrc != null) dropAll(true);
        lastSrc = javaSrc;
      }
      if (!classesDir.equals(lastOut)) {
        Files.createDirectories(classesDir);
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir));
        if (lastOut != null) dropAll(true);
        lastOut = classesDir;
        abiCache.clear();
        outputsOf.clear();
      }
    }

    private List<String> options(int release, int mode) {
      List<String> opts = new ArrayList<>(List.of("-g", "-Xlint:none", "-nowarn"));
      if (procNone) opts.add("-proc:none"); // fără annotation processing: mai rapid
      if (mode == 0) {
        opts.add("--release");
        opts.add(String.valueOf(release));
      } else if (mode == 1) {
        opts.addAll(List.of("-source", String.valueOf(release), "-target", String.valueOf(release)));
      }
      return opts;
    }

    /** --release N doar dacă N e sub JDK-ul curent: altfel n-ar schimba nimic, dar costă câțiva ms la fiecare compilare. */
    private static boolean canRelease(int release) {
      return release >= 8 && release < Runtime.version().feature();
    }

    private List<String> poolOptions(int release) {
      List<String> opts = options(release, canRelease(release) ? releaseMode : 2);
      String key = String.join(" ", opts);
      if (!key.equals(lastOpts)) { // alte opțiuni = alt context în spatele aceluiași pool: nimic din ce era cald nu mai e
        if (lastOpts != null) dropAll(true);
        lastOpts = key;
      }
      return opts;
    }

    private JavaCompiler.CompilationTask newTask(
        JavaFileManager m, DiagnosticCollector<JavaFileObject> diags, Iterable<? extends JavaFileObject> units, int release) {
      while (true) {
        int mode = canRelease(release) ? releaseMode : 2;
        List<String> opts = options(release, mode);
        try {
          return jc.getTask(null, m, diags, opts, null, units);
        } catch (IllegalArgumentException e) {
          if (mode >= 2) throw e;
          releaseMode = mode + 1; // varianta următoare, care păstrează oricum versiunea corectă a claselor
        }
      }
    }

    private List<JavaFileObject> units(List<Path> files, Map<Path, byte[]> content) {
      List<JavaFileObject> out = new ArrayList<>(files.size());
      Iterator<Path> it = files.iterator();
      for (JavaFileObject fo : fm.getJavaFileObjectsFromPaths(files)) {
        byte[] b = content == null ? null : content.get(it.next());
        out.add(b == null ? fo : new SnapshotSource(fo, b, lastCharset));
      }
      return out;
    }

    private static boolean hasErrors(DiagnosticCollector<JavaFileObject> diags) {
      for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
        if (d.getKind() == Diagnostic.Kind.ERROR) return true;
      }
      return false;
    }

    private Result attempt(List<Path> files, Map<Path, byte[]> content, Path javaSrc, Path classesDir,
        List<Path> classpath, int release) throws IOException {
      setup(javaSrc, classesDir, classpath);
      Result res = new Result();
      DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
      boolean ok;
      if (!procNone || files.size() > MAX_IN_MEMORY_BATCH) {
        // procesatoare de adnotări (Lombok...) sau foarte multe fișiere: compilare obișnuită, javac scrie direct pe disc
        dropAll(false);
        abiCache.clear();
        outputsOf.clear();
        ok = newTask(fm, diags, fm.getJavaFileObjectsFromPaths(files), release).call();
        res.written = ok ? files.size() : 0;
      } else {
        List<JavaFileObject> units = units(files, content);
        Map<String, byte[]> outs = new LinkedHashMap<>();
        ofm.sink = outs;
        try {
          Object key = keyOf(files);
          TaskPool ctx = null;
          boolean reused = false;
          if (!poolOff) {
            List<String> opts = poolOptions(release); // poate arunca contextele, dacă opțiunile s-au schimbat
            if (!freshOnly.contains(key)) ctx = ctxs.remove(key);
            reused = ctx != null;
            if (ctx == null) {
              ctx = spare;
              spare = null;
              if (ctx != null) {
                poolSpare++;
              } else {
                ctx = TaskPool.create();
                poolCold++;
              }
            }
            if (ctx == null) {
              poolOff = true;
              ok = newTask(ofm, diags, units, release).call();
            } else {
              boolean ran = false;
              try {
                ctx.run(ofm, diags, opts, units);
                ran = true;
                ok = !hasErrors(diags);
              } catch (Throwable t) { // pool-ul nu merge aici (alt JDK...): compilare obișnuită, iar după 2 eșecuri fără pool
                ok = false;
                dropAll(true);
                if (++poolFailures >= 2) poolOff = true;
              }
              if (!ok) {
                // Un context nou, cu un singur fișier, nu poate da erori false: le raportăm direct. Altfel reconfirmăm cu
                // o compilare obișnuită (context curat), care - ca javac - lasă scrise clasele generate înainte de eroare.
                boolean trusted = ran && !reused && files.size() == 1;
                if (!trusted) {
                  outs.clear();
                  diags = new DiagnosticCollector<>();
                  ok = newTask(ofm, diags, units, release).call();
                  poolFallbacks++;
                  if (ok && ran) {
                    if (reused) {
                      if (freshOnly.size() < 1000) freshOnly.add(key); // eroare falsă a reutilizării: de acum, mereu rezerva
                    } else if (++poolFailures >= 2) {
                      poolOff = true;
                    }
                  }
                }
                ctx = null; // un context în care au apărut erori nu se păstrează
              }
            }
          } else {
            ok = newTask(ofm, diags, units, release).call();
          }
          if (!outs.isEmpty()) {
            if (commit(outs, classesDir, res)) { // s-a schimbat "interfața" unei clase (sau a apărut una nouă)
              dropAll(false);
              ctx = null;
            }
            if (ok) learnLibraries(outs.values(), javaSrc, classesDir);
            if (ok && files.size() == 1) removeOrphans(files.get(0), javaSrc, classesDir, outs.keySet(), res);
          }
          if (ok && ctx != null && files.size() <= 8 && !freshOnly.contains(key)) {
            putCtx(key, ctx);
            if (reused) poolHits++;
          }
        } finally {
          ofm.sink = null;
        }
      }
      for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
        if (d.getKind() != Diagnostic.Kind.ERROR) continue;
        String where =
            d.getSource() == null ? "" : new File(d.getSource().getName()).getName() + ":" + d.getLineNumber() + "  ";
        res.errors.add(where + d.getMessage(null));
      }
      if (!ok && res.errors.isEmpty()) res.errors.add("compilare eșuată");
      return res;
    }

    /**
     * Scrie clasele generate în Tomcat, pe loc și doar pe cele care diferă.
     *
     * @return true dacă s-a schimbat "interfața" vreunei clase sau a apărut o clasă nouă
     */
    private boolean commit(Map<String, byte[]> outs, Path classesDir, Result res) throws IOException {
      boolean abiChanged = false;
      for (Map.Entry<String, byte[]> e : outs.entrySet()) {
        String name = e.getKey();
        byte[] bytes = e.getValue();
        Path f = classesDir.resolve(name.replace('.', '/') + ".class");
        boolean local = Abi.isLocalName(name); // clase anonime/locale: nu le vede nimeni din afară
        String oldAbi = local ? null : abiCache.get(name);
        if (!local && oldAbi == null) {
          try {
            oldAbi = Abi.of(Files.readAllBytes(f));
          } catch (IOException ex) {
            // clasă nouă
          }
          if (oldAbi == null) oldAbi = "";
        }
        boolean wrote = writeInPlace(bytes, f);
        if (wrote) res.written++;
        else res.unchanged++;
        if (!local) {
          String abi = wrote || oldAbi.isEmpty() ? Abi.of(bytes) : oldAbi;
          if (abi == null || oldAbi.isEmpty() || !abi.equals(oldAbi)) abiChanged = true;
          if (abi != null) abiCache.put(name, abi);
          else abiCache.remove(name);
        }
      }
      return abiChanged;
    }

    /** "com.demo.web.Servlet0" pentru src/main/java/com/demo/web/Servlet0.java (null dacă fișierul nu e sub javaSrc). */
    private static String mainClassName(Path javaSrc, Path file) {
      if (!file.startsWith(javaSrc)) return null;
      String rel = javaSrc.relativize(file).toString().replace(File.separatorChar, '/');
      if (!rel.endsWith(".java")) return null;
      return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    /**
     * Șterge clasele interne/anonime (Nume$X.class) care nu mai sunt generate din fișier - au rămas de la o versiune mai
     * veche a lui. Doar pentru clasa cu numele fișierului: clasele ei interne nu pot veni din alt fișier.
     */
    private void removeOrphans(Path file, Path javaSrc, Path classesDir, Set<String> produced, Result res) {
      String main = mainClassName(javaSrc, file);
      if (main == null || !produced.contains(main)) return;
      String prefix = main + "$";
      Set<String> mine = new HashSet<>();
      for (String n : produced) if (n.equals(main) || n.startsWith(prefix)) mine.add(n);
      Set<String> prev = outputsOf.put(file, mine);
      List<String> stale = new ArrayList<>();
      if (prev != null) {
        for (String n : prev) if (!mine.contains(n)) stale.add(n);
      } else { // prima compilare a fișierului în această sesiune: ne uităm o singură dată în folder
        Path cls = classesDir.resolve(main.replace('.', '/') + ".class");
        Path dir = cls.getParent();
        String fileName = cls.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - ".class".length());
        String pkg = main.substring(0, main.length() - base.length());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, base + "$*.class")) {
          for (Path p : ds) {
            String fn = p.getFileName().toString();
            String n = pkg + fn.substring(0, fn.length() - ".class".length());
            if (!mine.contains(n)) stale.add(n);
          }
        } catch (IOException | RuntimeException e) {
          return;
        }
      }
      for (String n : stale) {
        try {
          if (Files.deleteIfExists(classesDir.resolve(n.replace('.', '/') + ".class"))) {
            res.removed.add(n.substring(n.lastIndexOf('.') + 1));
          }
          abiCache.remove(n);
        } catch (IOException e) {
          // rămâne; nu e critic
        }
      }
    }

    // ---------------------------------------------------------------------------------
    // Muncă în fundal: contextul de rezervă și încălzirea JIT
    // ---------------------------------------------------------------------------------
    /** O compilare sintetică în memorie: încarcă în context clasele uzuale ale JDK-ului, fără să atingă proiectul. */
    private void warmRound(TaskPool p, int release) throws Exception {
      ofm.sink = new HashMap<>();
      try {
        p.run(ofm, new DiagnosticCollector<JavaFileObject>(), poolOptions(release),
            List.of(new MemSource("PW", warmSource("PW"))));
      } finally {
        ofm.sink = null;
      }
    }

    /**
     * Încarcă în contextul de rezervă clasele din librării folosite de proiect (o clasă sintetică având câte un câmp de
     * fiecare tip). Doar clase din jar-uri/JDK: o clasă a proiectului nu trebuie să ajungă niciodată în rezervă.
     */
    private void primeLibraries(TaskPool p, int release) throws Exception {
      if (libTypes.isEmpty()) return;
      List<String> names = new ArrayList<>(libTypes);
      StringBuilder sb = new StringBuilder("package td.warm;\npublic class PL {\n");
      for (int i = 0; i < names.size(); i++) sb.append("  ").append(names.get(i)).append(" f").append(i).append(";\n");
      sb.append("}\n");
      DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
      ofm.sink = new HashMap<>();
      try {
        p.run(ofm, diags, poolOptions(release), List.of(new MemSource("PL", sb.toString())));
      } finally {
        ofm.sink = null;
      }
      for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) { // tip inaccesibil/inexistent: îl uităm
        int idx = (int) d.getLineNumber() - 3;
        if (d.getKind() == Diagnostic.Kind.ERROR && idx >= 0 && idx < names.size()) libTypes.remove(names.get(idx));
      }
    }

    private static boolean isIdentifier(String s) {
      if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
      for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
      return true;
    }

    /** Hotărăște (o singură dată per nume) dacă o clasă referită e din librării; cele din proiect sunt excluse. */
    private static Boolean classifyLibrary(String internal, Path javaSrc, Path classesDir) {
      if (internal.isEmpty() || internal.charAt(0) == '[') return Boolean.FALSE;
      int slash = internal.lastIndexOf('/');
      String simple = internal.substring(slash + 1);
      for (String part : simple.split("\\$", -1)) if (!isIdentifier(part)) return Boolean.FALSE; // anonime, sintetice...
      int d = internal.indexOf('$', slash + 1);
      String top = d < 0 ? internal : internal.substring(0, d);
      try {
        if (Files.exists(classesDir.resolve(top + ".class")) || Files.exists(javaSrc.resolve(top + ".java"))) return Boolean.FALSE;
      } catch (RuntimeException e) {
        return Boolean.FALSE;
      }
      return Boolean.TRUE;
    }

    private void learnLibraries(java.util.Collection<byte[]> classes, Path javaSrc, Path classesDir) {
      if (libTypes.size() >= MAX_LIB_TYPES) return;
      Set<String> refs = new HashSet<>();
      for (byte[] b : classes) Abi.classRefs(b, refs);
      for (String internal : refs) {
        Boolean lib = libSeen.get(internal);
        if (lib == null) libSeen.put(internal, lib = classifyLibrary(internal, javaSrc, classesDir));
        if (lib && libTypes.size() < MAX_LIB_TYPES) libTypes.add(internal.replace('/', '.').replace('$', '.'));
      }
    }

    /**
     * La pornirea deploy-ului: învață librăriile folosite din clasele deja compilate aflate în Tomcat (cel mult
     * {@code maxFiles} fișiere), ca și prima rezervă să fie pregătită pentru proiect. Citirile se fac în afara blocării.
     */
    void seedLibraries(Path javaSrc, Path classesDir, int maxFiles) {
      Set<String> refs = new HashSet<>();
      try (Stream<Path> st = Files.walk(classesDir)) {
        Iterator<Path> it = st.filter(x -> x.toString().endsWith(".class")).limit(maxFiles).iterator();
        while (it.hasNext() && !closed) {
          try {
            Abi.classRefs(Files.readAllBytes(it.next()), refs);
          } catch (IOException | RuntimeException e) {
            // fișierul următor
          }
        }
      } catch (IOException | RuntimeException e) {
        return;
      }
      Map<String, Boolean> decided = new HashMap<>();
      for (String internal : refs) decided.put(internal, classifyLibrary(internal, javaSrc, classesDir));
      synchronized (this) {
        for (Map.Entry<String, Boolean> e : decided.entrySet()) {
          if (libSeen.putIfAbsent(e.getKey(), e.getValue()) == null && e.getValue() && libTypes.size() < MAX_LIB_TYPES) {
            libTypes.add(e.getKey().replace('/', '.').replace('$', '.'));
          }
        }
      }
    }

    /** Creează un context de rezervă: clasele uzuale ale JDK-ului + librăriile folosite de proiect. */
    private TaskPool newSpare(int release) throws Exception {
      TaskPool p = TaskPool.create();
      if (p == null) return null;
      warmRound(p, release);
      primeLibraries(p, release);
      return p;
    }

    private void yieldToCompiles() throws InterruptedException {
      while (waiting.get() > 0 && !closed) Thread.sleep(2); // o salvare reală are prioritate
    }

    /** Pregătește (dacă lipsește) contextul de rezervă. Apelat din fundal după fiecare compilare care l-a consumat. */
    void refill(Path javaSrc, Path classesDir, List<Path> classpath, int release) {
      try {
        yieldToCompiles();
        synchronized (this) {
          if (closed || poolOff || !procNone || spare != null) return;
          setup(javaSrc, classesDir, classpath);
          TaskPool p = newSpare(release);
          if (p != null && !closed) spare = p;
        }
      } catch (Throwable t) {
        // doar o optimizare
      }
    }

    synchronized boolean hasSpare() {
      return spare != null;
    }

    /**
     * Încălzire: compilări în memorie care aduc JIT-ul lui javac la viteză (nu scriu nimic pe disc). Blocarea e pe
     * fiecare rundă în parte, iar între runde cedează locul compilărilor reale.
     */
    void warmUp(Path javaSrc, Path classesDir, List<Path> classpath, int release, int rounds, List<Path> sample)
        throws IOException {
      TaskPool jit = null;
      try {
        for (int i = 0; i < rounds && !closed; i++) {
          yieldToCompiles();
          synchronized (this) {
            if (closed) return;
            setup(javaSrc, classesDir, classpath);
            if (procNone && !poolOff) {
              try {
                if (spare == null) { // rezerva are prioritate: o salvare reală o poate consuma oricând
                  TaskPool p = newSpare(release);
                  if (p == null) poolOff = true;
                  else spare = p;
                } else {
                  if (jit == null) jit = TaskPool.create();
                  if (jit == null) poolOff = true;
                  else warmRound(jit, release);
                }
              } catch (Throwable t) {
                jit = null;
                if (++poolFailures >= 2) poolOff = true;
              }
              continue;
            }
            JavaFileManager sink =
                new ForwardingJavaFileManager<StandardJavaFileManager>(fm) {
                  @Override
                  public JavaFileObject getJavaFileForOutput(
                      Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                    return new MemClass(className);
                  }
                };
            try {
              // câte un fișier real pe rundă (pe rând): runde scurte, deci se cedează repede unei salvări reale
              Iterable<? extends JavaFileObject> units =
                  sample.isEmpty()
                      ? List.of(new MemSource("__Warmup", WARM_SRC))
                      : fm.getJavaFileObjectsFromPaths(List.of(sample.get(i % sample.size())));
              newTask(sink, new DiagnosticCollector<JavaFileObject>(), units, release).call();
            } catch (RuntimeException ignored) {
              // încălzirea e opțională
            }
          }
          Thread.yield();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static final String WARM_SRC =
        "package td.warm; import java.util.*; public class __Warmup { static int f(List<String> l) { int n = 0;"
            + " for (String s : l) n += s.length(); l.forEach(x -> { }); return n; }"
            + " static class In { void g(Map<String, Integer> m) { m.merge(\"a\", 1, Integer::sum); } } }";

    /** Închide jar-urile deschise; următoarea compilare repornește file manager-ul. */
    synchronized void invalidate() {
      dropAll(true);
      lastOpts = null;
      libTypes.clear();
      libSeen.clear();
      if (fm != null) {
        try {
          fm.close();
        } catch (IOException ignored) {
          // nimic
        }
        fm = null;
        ofm = null;
      }
    }

    @Override
    public synchronized void close() {
      closed = true;
      invalidate();
    }
  }

  /** O singură compilare, cu un compilator proaspăt (folosit și de teste). Întoarce erorile (gol = succes). */
  static List<String> compileSources(List<Path> files, Path javaSrc, Path classesDir, String classpath, int release)
      throws IOException {
    List<Path> cp = new ArrayList<>();
    for (String e : classpath.split(File.pathSeparator)) if (!e.isEmpty()) cp.add(Path.of(e));
    try (JavacSession s = new JavacSession()) {
      return s.compile(files, null, javaSrc, classesDir, cp, release).errors;
    }
  }

  /** Număr de runde de încălzire la pornirea aplicației (JIT-ul lui javac ajunge la viteză maximă după ~75). */
  static final int APP_WARM_ROUNDS = 60;
  /** Runde de încălzire cu fișiere reale din proiect, după deploy (în fundal, cedează locul salvărilor). */
  static final int WARM_ROUNDS = 240;

  /** Sursă sintetică, variată (generice, lambda, stream, record, switch, try-with-resources...): încălzește tot javac. */
  static String warmSource(String name) {
    return "package td.warm; import java.util.*; import java.util.function.*; import java.util.stream.*; import java.io.*;\n"
        + "public class " + name + " {\n"
        + "  enum Kind { A, B, C; int w() { return switch (this) { case A -> 1; case B -> 2; default -> 3; }; } }\n"
        + "  record Pair<K, V>(K k, V v) { <R> Pair<K, R> map(Function<? super V, ? extends R> f) { return new Pair<>(k, f.apply(v)); } }\n"
        + "  static abstract class Base<T extends Comparable<T>> { abstract T get(); int cmp(Base<T> o) { return get().compareTo(o.get()); } }\n"
        + "  private final Map<String, List<Integer>> m = new HashMap<>();\n"
        + "  private int counter;\n"
        + "  public static <T extends Comparable<? super T>> T max(Collection<? extends T> c) {\n"
        + "    Iterator<? extends T> it = c.iterator(); T best = it.next();\n"
        + "    while (it.hasNext()) { T x = it.next(); if (x.compareTo(best) > 0) best = x; } return best; }\n"
        + "  public String run(List<String> in, int... extra) throws IOException {\n"
        + "    StringBuilder sb = new StringBuilder();\n"
        + "    for (String s : in) { if (s == null || s.isEmpty()) continue; sb.append(s.trim()).append(':').append(s.length()).append(','); }\n"
        + "    Map<Boolean, List<String>> parts = in.stream().filter(Objects::nonNull).collect(Collectors.partitioningBy(s -> s.length() > 3));\n"
        + "    Optional<String> first = parts.get(true).stream().map(String::toUpperCase)\n"
        + "        .sorted(Comparator.comparing(String::length).thenComparing(Comparator.naturalOrder())).findFirst();\n"
        + "    try (StringReader r = new StringReader(sb.toString()); BufferedReader br = new BufferedReader(r)) {\n"
        + "      String line = br.readLine(); counter += line == null ? 0 : line.length();\n"
        + "    } catch (IOException | RuntimeException e) { throw new IOException(\"x\" + e.getMessage(), e); }\n"
        + "    for (int i = 0; i < extra.length; i++) counter += extra[i] * (i + 1);\n"
        + "    Runnable rr = () -> counter++; rr.run();\n"
        + "    Supplier<List<Pair<String, Integer>>> sup = () -> in.stream().map(s -> new Pair<>(s, s.length())).collect(Collectors.toList());\n"
        + "    Kind k = Kind.valueOf(\"A\"); counter += k.w();\n"
        + "    Object o = first.orElse(\"none\"); if (o instanceof String str && str.length() > 2) counter += str.length();\n"
        + "    return sb + \"|\" + first.orElse(\"\") + \"|\" + counter + \"|\" + sup.get().size() + \"|\" + max(Arrays.asList(1, 2, 3));\n"
        + "  }\n}\n";
  }

  /**
   * Încălzește compilatorul cât utilizatorul alege folderele: ~90 de compilări în memorie (nu scriu nimic pe disc).
   * JIT-ul lui javac e al întregului proces, deci după asta orice compilare reală rulează la viteza maximă
   * (~20 ms în loc de ~50-80 ms la prima salvare).
   */
  static void warmUpCompiler() {
    try {
      JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
      if (jc == null) return;
      try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
        JavaFileManager sink =
            new ForwardingJavaFileManager<StandardJavaFileManager>(fm) {
              @Override
              public JavaFileObject getJavaFileForOutput(
                  Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new JavacSession.MemClass(className);
              }
            };
        List<String> opts = List.of("-g", "-Xlint:none", "-nowarn", "-proc:none");
        for (int i = 0; i < APP_WARM_ROUNDS; i++) {
          String name = "W" + i;
          jc.getTask(null, sink, new DiagnosticCollector<JavaFileObject>(), opts, null,
                  List.of(new JavacSession.MemSource(name, warmSource(name))))
              .call();
        }
      }
    } catch (Throwable ignored) {
      // doar o optimizare: dacă nu merge, primele compilări sunt pur și simplu mai lente
    }
  }
}
