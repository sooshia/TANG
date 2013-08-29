package com.microsoft.tang.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;

import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.MethodParameterScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;

import com.microsoft.tang.ExternalConstructor;
import com.microsoft.tang.JavaClassHierarchy;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.DefaultImplementation;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.annotations.Unit;
import com.microsoft.tang.exceptions.ClassHierarchyException;
import com.microsoft.tang.exceptions.NameResolutionException;
import com.microsoft.tang.formats.ConfigurationModule;
import com.microsoft.tang.formats.ConfigurationModuleBuilder;
import com.microsoft.tang.types.ClassNode;
import com.microsoft.tang.types.ConstructorArg;
import com.microsoft.tang.types.ConstructorDef;
import com.microsoft.tang.types.NamedParameterNode;
import com.microsoft.tang.types.Node;
import com.microsoft.tang.types.PackageNode;
import com.microsoft.tang.util.walk.AbstractClassHierarchyNodeVisitor;
import com.microsoft.tang.util.walk.NodeVisitor;
import com.microsoft.tang.util.walk.Walk;

public class Tint {
  final JavaClassHierarchy ch;
  final Map<Field, ConfigurationModule> modules = new MonotonicHashMap<>();
  final MonotonicMultiMap<Node, Field> setters= new MonotonicMultiMap<>();
  // map from user to thing that was used.
  final MonotonicMultiMap<String, Node> usages= new MonotonicMultiMap<>();
  final private static String SETTERS = "setters";
  final private static String USES = "uses";
  final private static String FULLNAME = "fullName";
  final Set<ClassNode> knownClasses = new MonotonicSet<>();
  
  final private static int NUMCOLS = 3;
  
  final Set<String> divs = new MonotonicSet<>();
  {
    divs.add("doc");
    divs.add(USES);
    divs.add(SETTERS);
    
  }
  
  public Tint() {
    this(new URL[0]);
  }
  public Tint(URL[] jars) {
    this(jars, false);
  }

  public Tint(URL[] jars, boolean checkTang) {
    Object[] args = new Object[jars.length + 6];
    for(int i = 0; i < jars.length; i++){
      args[i] = jars[i];
    }
    args[args.length-1] = new TypeAnnotationsScanner();
    args[args.length-2] = new SubTypesScanner();
    args[args.length-3] = new MethodAnnotationsScanner();
    args[args.length-4] = new MethodParameterScanner();
    args[args.length-5] = "com.microsoft";
    args[args.length-6] = "org.apache";
    Reflections r = new Reflections(args);
    Set<Class<?>> classes = new MonotonicSet<>();
    Set<String> strings = new TreeSet<>();
    Set<String> moduleBuilders = new MonotonicSet<>();

    // Workaround bug in Reflections by keeping things stringly typed, and using Tang to parse them.
//  Set<Constructor<?>> injectConstructors = (Set<Constructor<?>>)(Set)r.getMethodsAnnotatedWith(Inject.class);
//  for(Constructor<?> c : injectConstructors) {
//    classes.add(c.getDeclaringClass());
//  }
    Set<String> injectConstructors = r.getStore().getConstructorsAnnotatedWith(ReflectionUtilities.getFullName(Inject.class));
    for(String s : injectConstructors) {
      strings.add(s.replaceAll("\\.<.+$",""));
    }
    Set<String> parameterConstructors = r.getStore().get(MethodParameterScanner.class, ReflectionUtilities.getFullName(Parameter.class));
    for(String s : parameterConstructors) {
      strings.add(s.replaceAll("\\.<.+$",""));
    }
//    Set<Class> r.getConstructorsWithAnyParamAnnotated(Parameter.class);
//    for(Constructor<?> c : parameterConstructors) {
//      classes.add(c.getDeclaringClass());
//    }
    Set<String> defaultStrings = r.getStore().get(TypeAnnotationsScanner.class, ReflectionUtilities.getFullName(DefaultImplementation.class));
    strings.addAll(defaultStrings);
    strings.addAll(r.getStore().get(TypeAnnotationsScanner.class, ReflectionUtilities.getFullName(NamedParameter.class)));
    strings.addAll(r.getStore().get(TypeAnnotationsScanner.class, ReflectionUtilities.getFullName(Unit.class)));
//    classes.addAll(r.getTypesAnnotatedWith(DefaultImplementation.class));
//    classes.addAll(r.getTypesAnnotatedWith(NamedParameter.class));
//    classes.addAll(r.getTypesAnnotatedWith(Unit.class));
    
    strings.addAll(r.getStore().get(SubTypesScanner.class, ReflectionUtilities.getFullName(Name.class)));
    
    moduleBuilders.addAll(r.getStore().get(SubTypesScanner.class, ReflectionUtilities.getFullName(ConfigurationModuleBuilder.class)));
//    classes.addAll(r.getSubTypesOf(Name.class));

    ch = Tang.Factory.getTang().getDefaultClassHierarchy(jars, new Class[0]);
    for(String s : defaultStrings) {
      try {
        if(checkTang || !s.startsWith("com.microsoft.tang")) {
        try {
          DefaultImplementation di = ch.classForName(s).getAnnotation(DefaultImplementation.class);
          // XXX hack: move to helper method + unify with rest of Tang!
          String diName = di.value() == Void.class ? di.name() : ReflectionUtilities.getFullName(di.value());
          strings.add(diName);
              usages.put(diName, ch.getNode(s));
          } catch(ClassHierarchyException | NameResolutionException e) {
            System.err.println(e.getMessage());
          }
        }
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
    
    for(String s : strings) {
      try {
        // Tang's unit tests include a lot of nasty corner cases; no need to look at those!
        if(checkTang || !s.startsWith("com.microsoft.tang")) {
          ch.getNode(s);
        }
      } catch(ClassHierarchyException | NameResolutionException e) {
        System.err.println(e.getMessage());
      }
    }
    for(String mb : moduleBuilders) {
      if(checkTang || !mb.startsWith("com.microsoft.tang")) {
        try {
          @SuppressWarnings("unchecked")
          Class<ConfigurationModuleBuilder> cmb = (Class<ConfigurationModuleBuilder>) ch.classForName(mb);
          for(Field f : cmb.getFields()) {
            if(ReflectionUtilities.isCoercable(ConfigurationModule.class, f.getType())) {
  
              int mod = f.getModifiers();
              if(!Modifier.isPrivate(mod)) {
                if(!Modifier.isFinal(mod)) {
                  System.err.println("Style warning: Found non-final ConfigurationModule" + f);
                }
                if(!Modifier.isStatic(f.getModifiers())) {
                  System.err.println("Style warning: Found non-static ConfigurationModule " + f);
                } else {
                  try {
                    f.setAccessible(true);
                    modules.put(f,(ConfigurationModule)(f.get(null)));
                    for(Entry<String, String> e : modules.get(f).toStringPairs()) {
                      try {
                        Node n = ch.getNode(e.getKey());
                        /// XXX would be great if this referred to the field in question (not the conf)
                        setters.put(n,f);
                      } catch(NameResolutionException ex) {
                        
                      }
                      try {
                        Node n = ch.getNode(e.getValue());
                        /// XXX would be great if this referred to the field in question (not the conf)
                        usages.put(ReflectionUtilities.getFullName(f),n);
                      } catch(NameResolutionException ex) {
                        
                      }
                    }
                  } catch(ExceptionInInitializerError e) {
                    System.err.println("Field " + ReflectionUtilities.getFullName(f) + ": " + e.getCause().getMessage());
                  } catch(IllegalAccessException e) {
                    throw new RuntimeException(e);
                  }
                }
              }
            }
          }
        } catch(ClassNotFoundException e) {
          e.printStackTrace();
        }
      }
    }
    for(Class<?> c : classes) {
      strings.add(ReflectionUtilities.getFullName(c));
    }

    NodeVisitor<Node> v = new AbstractClassHierarchyNodeVisitor() {
      
      @Override
      public boolean visit(NamedParameterNode<?> node) {
        if(node.getDefaultInstanceAsString() != null &&
            !usages.contains(node.getDefaultInstanceAsString(), node)) {
          usages.put(node.getDefaultInstanceAsString(), node);
        }
        return true;
      }
      
      @Override
      public boolean visit(PackageNode node) {
        return true;
      }
      
      @Override
      public boolean visit(ClassNode<?> node) {
        for(ConstructorDef<?> d : node.getInjectableConstructors()) {
          for(ConstructorArg a : d.getArgs()) {
            if(a.getNamedParameterName() != null &&
                !usages.contains(a.getNamedParameterName(), node)) {
              usages.put(a.getNamedParameterName(), node);
            }
          }
        }
        knownClasses.add(node);
        return true;
      }
    };
    Walk.preorder(v, null, ch.getNamespace());

    for(Field f : modules.keySet()) {
      ConfigurationModule m = modules.get(f);
      Set<NamedParameterNode<?>> nps = m.getBoundNamedParameters();
      for(NamedParameterNode<?> np : nps) {
        if(!setters.contains(np, f)) {
          setters.put(np, f);
        }
      }
    }
  }
  
  public Set<NamedParameterNode<?>> getNames() {
    final Set<NamedParameterNode<?>> names = new MonotonicSet<>();
    NodeVisitor<Node> v = new AbstractClassHierarchyNodeVisitor() {
      
      @Override
      public boolean visit(NamedParameterNode<?> node) {
        names.add(node);
        return true;
      }
      
      @Override
      public boolean visit(PackageNode node) {
        return true;
      }
      
      @Override
      public boolean visit(ClassNode<?> node) {
        return true;
      }
    };
    Walk.preorder(v, null, ch.getNamespace());
    return names;
  }

  public Set<Node> getNamesUsedAndSet() {
    final Set<Node> names = new MonotonicSet<>();
    final Set<String> usedKeys = usages.keySet();
    final Set<Node> setterKeys = setters.keySet();
    NodeVisitor<Node> v = new AbstractClassHierarchyNodeVisitor() {

      @Override
      public boolean visit(NamedParameterNode<?> node) {
        names.add(node);
        return true;
      }
      
      @Override
      public boolean visit(PackageNode node) {
        return true;
      }
      
      @Override
      public boolean visit(ClassNode<?> node) {
        if(usedKeys.contains(node.getFullName())) {
          names.add(node);
        }
        if(setterKeys.contains(node)) {
          names.add(node);
        }
        
        return true;
      }
    };
    Walk.preorder(v, null, ch.getNamespace());
    return names;
  }

  public Set<Node> getUsesOf(final Node name) {

    return usages.getValuesForKey(name.getFullName());
  }
  public Set<Field> getSettersOf(final Node name) {
    return setters.getValuesForKey(name);
  }
  public static String stripCommonPrefixes(String s) {
    return 
        stripPrefixHelper2(
        stripPrefixHelper2(s, "java.lang"),
        "java.util");
  }
  public static String stripPrefixHelper2(String s, String prefix) {
    String[] pretok = prefix.split("\\.");
    String[] stok = s.split("\\.");
    StringBuffer sb = new StringBuffer();
    int i;
    for(i = 0; i < pretok.length && i < stok.length; i++) {
      if(pretok[i].equals(stok[i])) {
        sb.append(pretok[i].charAt(0)+".");
      } else{
        break;
      }
    }
    for(; i < stok.length-1; i++) {
      sb.append(stok[i]+".");
    }
    sb.append(stok[stok.length-1]);
    return sb.toString();
  }
/*  public static String stripPrefixHelper(String s, String prefix) {
    if(!"".equals(prefix) && s.startsWith(prefix+".")) {
      try {
        String suffix = s.substring(prefix.length()+1);
        if(suffix.matches("^[A-Z].+")){
          return suffix;
        } else {
          String shorten = prefix.replaceAll("([^.])[^.]+", "$1");
          return shorten + "." + suffix;
        }
      } catch(StringIndexOutOfBoundsException e) {
        throw new RuntimeException("Couldn't strip " + prefix + " from " + s, e);
      }
    } else {
      return s;
    }
  }*/
  public static String stripPrefix(String s, String prefix) {
    return stripPrefixHelper2(stripPrefixHelper2(stripPrefixHelper2(
        stripCommonPrefixes(stripPrefixHelper2(s,prefix)),
        "org.apache.reef"),"com.microsoft.reef"),"com.microsoft.wake");
  }
  public String toString(NamedParameterNode n) {
    StringBuilder sb = new StringBuilder("Name: " + n.getSimpleArgName() + " " + n.getFullName());
    if(n.getDefaultInstanceAsString() != null) {
      sb.append(" = " + n.getDefaultInstanceAsString());
    }
    if(!n.getDocumentation().equals("")) {
      sb.append(" //" + n.getDocumentation());
    }
    return sb.toString();
  }

  public String cell(String s, String clazz) {
    if(clazz.equals(USES) && s.length()>0) {
      s = "<em>Used by:</em><br>" + s;
    }
    if(clazz.equals(SETTERS) && s.length()>0) {
      s = "<em>Set by:</em><br>" + s;
    }
    if(clazz.equals(FULLNAME)) {
      s = "&nbsp;" + s;
    }
    if(divs.contains(clazz)) {
      return "<div class=\""+clazz+"\">" + s + "</div>";
    } else {
      return "<span class=\""+clazz+"\">" + s + "</span>";
    }
  }
  public String cell(StringBuffer sb, String clazz) {
    return cell(sb.toString(), clazz);
  }
  public String row(StringBuffer sb) {
    return sb.toString();
  }
  public String toHtmlString(NamedParameterNode<?> n, String pack) {
    String fullName = stripPrefix(n.getFullName(), pack);
    StringBuffer sb = new StringBuffer();

    sb.append("<div id='"+n.getFullName()+"' class='decl-margin'>");
    sb.append("<div class='decl'>");
    
    sb.append(cell(n.getSimpleArgName(), "simpleName") + cell(fullName, FULLNAME));
    final String instance;
    if(n.getDefaultInstanceAsString() != null) {
      instance = " = " + stripPrefix(n.getDefaultInstanceAsString(), pack);
    } else {
      instance = "";
    }
    sb.append(cell(instance, "instance"));
    StringBuffer doc = new StringBuffer();
    if(!n.getDocumentation().equals("")) {
      doc.append(n.getDocumentation());
    }
    sb.append(cell(doc, "doc"));
    StringBuffer uses = new StringBuffer();
    for(Node u : getUsesOf(n)) {
      uses.append("<a href='#"+u.getFullName()+"'>"+stripPrefix(u.getFullName(), pack) + "</a> ");
    }
    sb.append(cell(uses, USES));
    StringBuffer setters = new StringBuffer();
    for(Field f : getSettersOf(n)) {
      setters.append("<a href='#"+ReflectionUtilities.getFullName(f)+"'>"+stripPrefix(ReflectionUtilities.getFullName(f), pack) + "</a> ");
    }
    sb.append(cell(setters, SETTERS));
    sb.append("</div>");
    sb.append("</div>");
       return row(sb);
  }
  public String toHtmlString(ClassNode<?> n, String pack) {
    String fullName = stripPrefix(n.getFullName(), pack);
    
    final String type;
    try {
      if(ch.classForName(n.getFullName()).isInterface()) {
        type = "interface";
      } else {
        type = "class";
      }
    } catch(ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    StringBuffer sb = new StringBuffer();
 
    sb.append("<div class='decl-margin' id='"+n.getFullName()+"'>");
    sb.append("<div class='decl'>");
    sb.append(cell(type, "simpleName") + cell(fullName, FULLNAME));
    final String instance;
    if(n.getDefaultImplementation() != null) {
      instance = " = " + stripPrefix(n.getDefaultImplementation(), pack);
    } else {
      instance = "";
    }
    sb.append(cell(instance, "simpleName"));
    sb.append(cell("", "fullName")); // TODO: Documentation string?
    StringBuffer uses = new StringBuffer();
    for(Node u : getUsesOf(n)) {
      uses.append("<a href='#"+u.getFullName()+"'>"+stripPrefix(u.getFullName(), pack) + "</a> ");
    }
    sb.append(cell(uses, USES));
    StringBuffer setters = new StringBuffer();
    for(Field f : getSettersOf(n)) {
      setters.append("<a href='#"+ReflectionUtilities.getFullName(f)+"'>"+stripPrefix(ReflectionUtilities.getFullName(f), pack) + "</a> ");
    }
    sb.append(cell(setters, SETTERS));
    sb.append("</div>");
    sb.append("</div>");
    return row(sb);
  }
  public String startPackage(String pack) {
    return "<div class=\"package\">" + pack + "</div>";
//    return "<tr><td colspan='6'><br><b>" + pack + "</b></td></tr>";
  }
  public String endPackage() {
    return "";
  }
  
  /**
   * @param args
   * @throws FileNotFoundException 
   * @throws MalformedURLException 
   */
  public static void main(String[] args) throws FileNotFoundException, MalformedURLException {
    int i = 0;
    String doc = null;
    String jar = null;
    boolean tangTests = false;
    while(i < args.length) {
      if(args[i].equals("--doc")) {
        i++;
        doc = args[i];
      } else if(args[i].equals("--jar")) {
        i++;
        jar = args[i];
      } else if(args[i].equals("--tang-tests")) {
        tangTests = true;
      }
      
      i++;
    }
    final Tint t;
    if(jar != null) {
      File f = new File(jar);
      if(!f.exists()) { throw new FileNotFoundException(jar); }
      t = new Tint(new URL[] { f.toURI().toURL() }, tangTests);
    } else {
      t = new Tint(new URL[0], tangTests);
    }
    if(doc != null) {
      PrintStream out = new PrintStream(new FileOutputStream(new File(doc)));
      out.println("<html><head><title>TangDoc</title>");
      
      out.println("<style>");
      out.println("body { font-family: 'Segoe UI', 'Comic Sans MS'; font-size:12pt; font-weight: 200; margin: 1em; column-count: 2; }" );
      out.println(".package { font-size:18pt; font-weight: 500; column-span: all; }" );
//      out.println(".class { break-after: never; }");
//      out.println(".doc { break-before: never; }");
      out.println(".decl-margin { padding: 8pt; break-inside: avoid; }");
      out.println(".module-margin { padding: 8pt; column-span: all; break-inside: avoid; }");
      out.println(".decl { background-color: aliceblue; padding: 6pt;}");
      out.println(".fullName { font-size: 11pt; font-weight: 400; }");
      out.println(".simpleName { font-size: 11pt; font-weight: 400; }");
      out.println(".constructorArg { padding-left: 16pt; }");
      out.println("."+SETTERS+" { padding-top: 6pt; font-size: 10pt; }");
      out.println("."+USES+" { padding-top: 6pt; font-size: 10pt; }");
      out.println("pre { font-size: 10pt; }");
      out.println("</style>");
    
      out.println("</head><body>");
//      out.println("<table border='1'><tr><th>Type</th><th>Name</th><th>Default value</th><th>Documentation</th><th>Used by</th><th>Set by</th></tr>");

      String currentPackage = "";
//      int numcols = 0;
      for(Node n : t.getNamesUsedAndSet()) {
        String fullName = n.getFullName();
        String tok[] = fullName.split("\\.");
        StringBuffer sb = new StringBuffer(tok[0]);
        for(int j = 1; j < tok.length; j++) {
          if(tok[j].matches("^[A-Z].*") || j > 4) {
            break;
          } else
            sb.append("." + tok[j]);
        }
        String pack = sb.toString();
        if(!currentPackage.equals(pack)) {
          currentPackage = pack;
          out.println(t.endPackage());
          out.println(t.startPackage(currentPackage));
//          numcols = 0;
//          out.println("<div class='row'>");
        }
//        numcols++;
//        if(numcols == NUMCOLS) {
//          out.println("</div><div class='row'>");
//        }
        if(n instanceof NamedParameterNode<?>) {
          out.println(t.toHtmlString((NamedParameterNode<?>)n, currentPackage));
        } else if (n instanceof ClassNode<?>) {
          out.println(t.toHtmlString((ClassNode<?>)n, currentPackage));
        } else {
          throw new IllegalStateException();
        }
      }
      out.println("</div>");
      out.println(t.endPackage());
//      out.println("</table>");
      out.println("<div class='package'>Module definitions</div>");
      for(Field f : t.modules.keySet()) {
        String moduleName = ReflectionUtilities.getFullName(f);
        String declaringClassName = ReflectionUtilities.getFullName(f.getDeclaringClass());
        out.println("<div class='module-margin' id='"+moduleName+"'><div class='decl'><span class='fullName'>" + moduleName + "</span>");
        out.println("<pre>");
        String conf = t.modules.get(f).toPrettyString();
        String[] tok = conf.split("\n");
        for(String line : tok) {
          out.println(stripPrefix(line, "no.such.prefix"));//t.modules.get(f).toPrettyString());
        }
//        List<Entry<String,String>> lines = t.modules.get(f).toStringPairs();
//        for(Entry<String,String> line : lines) {
//          String k = t.stripPrefix(line.getKey(), declaringClassName);
//          String v = t.stripPrefix(line.getValue(), declaringClassName);
//          out.println(k+"="+v);
//        }
        out.println("</pre>");
        out.println("</div></div>");
      }
      out.println("<div class='package'>Interfaces and injectable classes</div>");
      for(ClassNode c : t.knownClasses) {
        Class<?> clz = null;
        try {
          clz = t.ch.classForName(c.getFullName());
        } catch (ClassNotFoundException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        String typ = clz.isInterface() ? "interface" : "class";
        out.println("<div class='module-margin' id='"+c.getFullName()+"'><div class='decl'><span class='fullName'>" + typ + " " + c.getFullName() + "</span>");
        for(ConstructorDef d : c.getInjectableConstructors()) {
          out.println("<div class='uses'>" + c.getFullName() + "(");
          for(ConstructorArg a : d.getArgs()) {
            if(a.getNamedParameterName() != null) {
              out.print("<div class='constructorArg'><a href='#"+a.getType()+"'>" + stripPrefix(a.getType(),"xxx") + "</a> <a href='#" + a.getNamedParameterName() + "'>" + a.getNamedParameterName() + "</a></div>");
            } else {
              out.print("<div class='constructorArg'><a href='#"+a.getType()+"'>" + stripPrefix(a.getType(),"xxx") + "</a></div>");
            }
          }
          out.println(")</div>");
        }
        
        out.println("</div></div>");
      }
/*
      out.println("<h1>Default usage of classes and constants</h1>");
      for(String s : t.usages.keySet()) {
        out.println("<h2>" + s + "</h2>");
        for(Node n : t.usages.getValuesForKey(s)) {
          out.println("<p>" + n.getFullName() + "</p>");
        }
      } */
      out.println("</body></html>");
      out.close();
      
    }
  }

}