package com.microsoft.tang;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Namespace;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.exceptions.NameResolutionException;

public class TypeHierarchy {
  private final Map<ClassNode<?>, List<ClassNode<?>>> knownImpls = new HashMap<ClassNode<?>, List<ClassNode<?>>>();
  private final Map<String, NamedParameterNode<?>> shortNames = new HashMap<String, NamedParameterNode<?>>();
  final PackageNode namespace = new PackageNode(null, "");
  final static String regexp = "[\\.\\$]";

  public void writeJson(OutputStream out) throws JsonGenerationException, JsonMappingException, IOException {
    ObjectMapper map = new ObjectMapper();
    map.defaultPrettyPrintingWriter().writeValue(out, this);
  }
  abstract class Node {
    protected final Node parent;
    protected final String name;
    String getFullName() {
      if(parent == null) {
        return name;
      } else {
        return parent.getFullName() + "." + name;
      }
    }
    Map<String, Node> children = new HashMap<String, Node>();

    Node(Node parent, Class<?> name) {
      this.parent = parent;
      this.name = name.getSimpleName();
      if (parent != null) {
        parent.put(this);
      }
    }

    Node(Node parent, String name) {
      this.parent = parent;
      this.name = name;
      if (parent != null) {
        parent.put(this);
      }
    }

    public boolean contains(String key) {
      return children.containsKey(key);
    }

    public Node get(String key) {
      return children.get(key);
    }

    void put(Node n) {
      Node old = children.put(n.name,  n);
      if(old != null) {
        throw new IllegalStateException(
            "Attempt to register node that already exists!");
      }
    }

    /*
     * public void addNamedParameter(NamedParameter name, Class<?> nameClazz) {
     * put(new NamedParameterNode(name, nameClazz));
     * 
     * }
     */

    public String toIndentedString(int level) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < level; i++) {
        sb.append("\t");
      }
      sb.append(toString() + "\n");
      if (children != null) {
        for (Node n : children.values()) {
          sb.append(n.toIndentedString(level + 1));
        }
      }
      return sb.toString();
    }

    @Override
    public String toString() {
      return "[" + this.getClass().getSimpleName() + " " + name + "]";
    }
    public String getType() { return this.getClass().getSimpleName(); }
    public String getName() { return name; }
    public Collection<Node> getChildren() { return children.values(); }
  }

  class PackageNode extends Node {
    PackageNode(Node parent, String name) {
      super(parent, name);
    }
  }

  class ClassNode<T> extends Node {
    final Class<T> clazz;
    final boolean isPrefixTarget;
    final ConstructorDef[] injectableConstructors;
    public Class<T> getClazz() {
      return clazz;
    }
    public boolean getIsPrefixTarget() {
      return isPrefixTarget;
    }
    public ConstructorDef[] getInjectableConstructors() {
      return injectableConstructors;
    }
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(super.toString() + ": ");
      for (ConstructorDef c : injectableConstructors) {
        sb.append(c.toString() + ", ");
      }
      return sb.toString();
    }

    public ClassNode(Node parent, Class<T> clazz, boolean isPrefixTarget) {
      super(parent, clazz);
      this.clazz = clazz;
      this.isPrefixTarget = isPrefixTarget;

      // Don't support non-static member classes with @Inject annotations.
      boolean injectable = true;
      if (clazz.isLocalClass() || clazz.isMemberClass()) {
        if (!Modifier.isStatic(clazz.getModifiers())) {
          injectable = false;
        }
      }

      //boolean injectAllConstructors = (clazz.getAnnotation(Inject.class) != null);
      Constructor<?>[] constructors = clazz.getDeclaredConstructors();
      List<ConstructorDef> injectableConstructors = new ArrayList<ConstructorDef>();
      /*if (injectAllConstructors && !injectable) {
        throw new IllegalArgumentException(
            "Cannot @Inject non-static member/local class: " + clazz);
      }*/

      for (int k = 0; k < constructors.length; k++) {

        if (constructors[k].getAnnotation(Inject.class) != null) {
          if (!injectable) {
            throw new IllegalArgumentException(
                "Cannot @Inject non-static member/local class: " + clazz);
          }
          // go through the constructor arguments.
          if (constructors[k].isSynthetic()) {
            throw new IllegalStateException(
                "Synthetic constructor was annotated with @Inject!");
          }

          // ConstructorDef's constructor checks for duplicate
          // parameters
          // The injectableConstructors set checks for ambiguous
          // constructors.
          Class<?>[] paramTypes = constructors[k].getParameterTypes();
          Annotation[][] paramAnnotations = constructors[k]
              .getParameterAnnotations();
          if (paramTypes.length != paramAnnotations.length) {
            throw new IllegalStateException();
          }
          ConstructorArg[] args = new ConstructorArg[paramTypes.length];
          for (int i = 0; i < paramTypes.length; i++) {
            // if there is an appropriate annotation, use that.
            Parameter named = null;
            for (int j = 0; j < paramAnnotations[i].length; j++) {
              Annotation annotation = paramAnnotations[i][j];
              if (annotation instanceof Parameter) {
                named = (Parameter) annotation;
                // Register the Parameter type, if necessary.
                Node n;
                try {
                  n = getNode(named.value());
                } catch (NameResolutionException e) {
                  n = buildPathToNode(named.value(), false);
                }
                if (!(n instanceof NamedParameterNode)) {
                  throw new IllegalStateException();
                }
                NamedParameterNode<?> np = (NamedParameterNode<?>) n;
                if (!ReflectionUtilities
                    .isCoercable(paramTypes[i], np.argClass)) {
                  throw new IllegalArgumentException(
                      "Incompatible argument type.  Constructor expects "
                          + paramTypes[i] + " but " + np.name + " is a "
                          + np.argClass);
                }
              }
            }
            args[i] = new ConstructorArg(paramTypes[i], named);
          }
          ConstructorDef def = new ConstructorDef(args, constructors[k]);
          if (injectableConstructors.contains(def)) {
            throw new IllegalStateException(
                "Ambiguous constructors detected in class " + clazz + ": "
                    + def + " differs from some other " + " constructor only "
                    + "by parameter order.");
          } else {
            injectableConstructors.add(def);
          }
        }
      }
      this.injectableConstructors = injectableConstructors
          .toArray(new ConstructorDef[0]);
    }
  }

  class NamespaceNode<T> extends Node {
    private ClassNode<T> target;

    public NamespaceNode(Node parent, String name, ClassNode<T> target) {
      super(parent, name);
      if (!target.isPrefixTarget) {
        throw new IllegalStateException();
      }
      this.target = target;
    }
    public NamespaceNode(Node parent, String name) {
      super(parent, name);
    }

    public void setTarget(ClassNode<T> target) {
      if(this.target != null) {
        throw new IllegalStateException("Attempt to set namespace target from " + this.target + " to " + target);
      }
      this.target = target;
      if (!target.isPrefixTarget) {
        throw new IllegalStateException();
      }
    }
    public Node getTarget() {
      return target;
    }
    @Override
    public String toString() {
      if (target != null) {
        return super.toString() + " -> " + target.toString();
      } else {
        return super.toString();
      }
    }
  }

  class NamedParameterNode<T> extends Node {
    final Class<? extends Name<T>> clazz;
    private final NamedParameter namedParameter;
    final Class<T> argClass;
    final Object defaultInstance;
    
    /*
     * NamedParameterNode(NamedParameter n, Class<?> nameClazz) {
     * super(nameClazz); children = null; this.namedParameter = n; this.argClass
     * = n.type(); }
     */

    public boolean isAsSpecificAs(NamedParameterNode<?> n) {
      if (!argClass.equals(n.argClass)) {
        return false;
      }
      if (!name.equals(n.name)) {
        return false;
      }
      if (n.namedParameter == null) {
        return true;
      }
      if (this.namedParameter == null) {
        return false;
      }
      return this.namedParameter.equals(n.namedParameter);
    }

    @SuppressWarnings("unchecked")
    NamedParameterNode(Node parent, Class<? extends Name<T>> clazz) {
      super(parent, clazz);
      this.clazz = clazz;

      for (Constructor<?> c : clazz.getDeclaredConstructors()) {
        for (Annotation a : c.getDeclaredAnnotations()) {
          if (a instanceof Inject) {
            throw new IllegalStateException(
                "Detected illegal @Injectable parameter class");
          }
        }
      }
      Class<T> parameterClass;
      try {
        Type[] interfaces = clazz.getGenericInterfaces();
        if(interfaces.length != 1) { throw new IllegalArgumentException(); }
        Type genericNameType = interfaces[0];
        if(genericNameType instanceof ParameterizedType) {
          ParameterizedType ptype = (ParameterizedType)genericNameType;
          if(ptype.getRawType() != Name.class) { throw new IllegalArgumentException(); }
          try {
            Type t = ptype.getActualTypeArguments()[0];
            // It could be that the parameter is, itself a generic type.  Not sure if we should support this, but we do for now.
            if(t instanceof ParameterizedType) {
              t = ((ParameterizedType) t).getRawType();  // Get the underlying raw type of the parameter.
            }
            parameterClass = (Class<T>)t;
          } catch(ClassCastException e) {
            throw new IllegalArgumentException();
          }
        } else {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("NamedParameter " + clazz + " must have exactly one super interface: A name with a concrete type parameter.");
      }

      
      this.namedParameter = clazz.getAnnotation(NamedParameter.class);
      this.argClass = parameterClass;
      this.defaultInstance = (this.namedParameter == null || namedParameter.default_value().length()==0) ?
          null : ReflectionUtilities.parse(this.argClass, namedParameter.default_value());
    }

    @Override
    public String toString() {
      String ret = argClass.getSimpleName();
      if (namedParameter == null) {
        ret = ret + " " + name;
      } else {
        ret = ret + " @Parameter(" + name + ")";
      }
      return ret;
    }

    public Class<T> getArgClass() {
      return argClass;
    }
    public Class<? extends Name<T>> getNameClass() {
      return clazz;
    }
    public Object getDefaultInstance() {
      return defaultInstance;
    }
    public String getDocumentation() {
      if(namedParameter != null) {
        return namedParameter.doc();
      } else {
        return "";
      }
    }
/*    public NamedParameter getNamedParameter() {
      return namedParameter;
    } */
    public String getShortName() {
      if (namedParameter.short_name() != null
          && namedParameter.short_name().length() == 0) {
        return null;
      }
      return namedParameter.short_name();
    }
  }

  class ConstructorArg {
    final Class<?> type;
    final Parameter name;
    public String getType() {
      return type.toString();
    }
    public String getName() {
      return name == null ? type.getName() : name.value().getName();
    }

    // TODO: Delete this method if we finalize the "class as name" approach to
    // named parameters
    /*String getFullyQualifiedName(Class<?> targetClass) {
      String name = getName();
      if (!name.contains(".")) {
        name = targetClass.getName() + "." + name;
        throw new IllegalStateException("Should be dead code now!");
      }
      return name;
    }*/

    ConstructorArg(Class<?> argType) {
      this.type = argType;
      this.name = null;
    }

    ConstructorArg(Class<?> type, Parameter name) {
      this.type = type;
      this.name = name;
    }

    @Override
    public String toString() {
      return name == null ? type.getSimpleName()
          : (type.getSimpleName() + " " + name.value().getSimpleName());
    }

    @Override
    public boolean equals(Object o) {
      ConstructorArg arg = (ConstructorArg) o;
      if (!type.equals(arg.type)) {
        return false;
      }
      if (name == null && arg.name == null) {
        return true;
      }
      if (name == null && arg.name != null) {
        return false;
      }
      if (name != null && arg.name == null) {
        return false;
      }
      return name.equals(arg.name);

    }
  }

  class ConstructorDef {
    final ConstructorArg[] args;
    final Constructor<?> constructor;
    public Class<?> getConstructor() {
      return constructor.getDeclaringClass();
    }
    public ConstructorArg[] getArgs() {
      return args;
    }
    @Override
    public String toString() {
      if (args.length == 0) {
        return "()";
      }
      StringBuilder sb = new StringBuilder("(" + args[0]);
      for (int i = 1; i < args.length; i++) {
        sb.append("," + args[i]);
      }
      sb.append(")");
      return sb.toString();
    }

    ConstructorDef(ConstructorArg[] args, Constructor<?> constructor) {
      this.args = args;
      this.constructor = constructor;
      constructor.setAccessible(true);

      for (int i = 0; i < this.args.length; i++) {
        for (int j = i + 1; j < this.args.length; j++) {
          if (this.args[i].toString().equals(this.args[j].toString())) {
            throw new IllegalArgumentException(
                "Repeated constructor parameter detected.  "
                    + "Cannot inject this constructor.");
          }
        }
      }
    }

    /**
     * Check to see if two constructors take indistinguishable arguments. If so
     * (and they are in the same class), then this would lead to ambiguous
     * injection targets, and we want to fail fast.
     * 
     * TODO could be faster. Currently O(n^2) in number of parameters.
     * 
     * @param def
     * @return
     */
    boolean equalsIgnoreOrder(ConstructorDef def) {
      if (args.length != def.args.length) {
        return false;
      }
      for (int i = 0; i < args.length; i++) {
        boolean found = false;
        for (int j = 0; j < args.length; j++) {
          if (args[i].getName().equals(args[j].getName())) {
            found = true;
          }
        }
        if (!found) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean equals(Object o) {
      return equalsIgnoreOrder((ConstructorDef) o);
    }

    public boolean isMoreSpecificThan(ConstructorDef def) {
      for (int i = 0; i < args.length; i++) {
        boolean found = false;
        for (int j = 0; j < def.args.length; j++) {
          if (args[i].equals(def.args[j])) {
            found = true;
          }
        }
        if (found == false)
          return false;
      }
      return args.length > def.args.length;
    }
  }

  @SuppressWarnings("unchecked")
  private <T> NamespaceNode<T> registerNamespace(Namespace conf,
      ClassNode<T> classNode) {
    String[] path = conf.value().split(regexp);
    Node root = namespace;
    for (int i = 0; i < path.length - 1; i++) {
      if (!root.contains(path[i])) {
        Node newRoot = new NamespaceNode<T>(root, path[i]);
        root = newRoot;
      } else {
        root = root.get(path[i]);
        if (!(root instanceof NamespaceNode)) {
          throw new IllegalArgumentException(
              "Attempt to register namespace inside of " + root
                  + " namespaces and java packages/classes cannot overlap.");
        }
      }
    }
    Node n = root.get(path[path.length-1]);
    NamespaceNode<T> ret;
    if(n == null) {
      ret = new NamespaceNode<T>(root, path[path.length - 1], classNode); 
    } else if (n instanceof NamespaceNode) {
      ret = (NamespaceNode<T>)n;
      ret.setTarget(classNode);
      for(Node child : ret.children.values()) {
        // TODO: Better error message here.  We're trying to merge two
        // namespaces.  If put throws an exception, it probably found a
        // conflicting node name.
        classNode.put(child);
      }
    } else {
        throw new IllegalArgumentException(
            "Attempt to register namespace on top of " + n
                + " namespaces and java packages/classes cannot overlap.");
    }
    return ret;
  }

  private <T, U> Node buildPathToNode(Class<U> clazz, boolean isPrefixTarget) {
    String[] path = clazz.getName().split(regexp);
    Node root = namespace;
    // Node ret = null;
    for (int i = 0; i < path.length - 1; i++) {
      root = root.get(path[i]);
    }

    if (root == null) {
      throw new NullPointerException();
    }
    Node parent = root;

    Node ret = null;
    if (clazz.getAnnotation(NamedParameter.class) != null) {
      if (isPrefixTarget) {
        throw new IllegalStateException(clazz
            + " cannot be both a namespace and parameter.");
      }
      @SuppressWarnings("unchecked") // checked inside of NamedParameterNode, using reflection.
      NamedParameterNode<T> np = new NamedParameterNode<T>(parent, (Class<? extends Name<T>>)clazz);
      //(Class<? extends Name<?>>)clazz);
      ret = np;
      String shortName = np.getShortName();
      if (shortName != null) {
        Node oldNode = shortNames.put(shortName, np);
        if (oldNode != null) {
          throw new IllegalStateException();
        }
      }
    } else {
      ret = new ClassNode<U>(parent, clazz, isPrefixTarget);
    }
    return ret;
  }

  Node getNode(Class<?> clazz) throws NameResolutionException {
    return getNode(clazz.getName());
  }

  Node getNode(String name) throws NameResolutionException {
    String[] path = name.split(regexp);
    return getNode(name, path, path.length);
  }

  private Node getNode(String name, String[] path, int depth)
      throws NameResolutionException {
    Node root = namespace;
    for (int i = 0; i < depth; i++) {
      if (root instanceof NamespaceNode) {
        NamespaceNode<?> ns = (NamespaceNode<?>)root;
        if(ns.target != null) {
          root = ns.target;
        }
      }
      root = root.get(path[i]);
      if (root == null) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < i; j++) {
          sb.append(path[j]);
          if (j != i - 1) {
            sb.append(".");
          }
        }
        throw new NameResolutionException(name, sb.toString());
      }
    }
    return root;
  }

  private String arrayToDotString(String[] array, int length) {
    StringBuilder parentString = new StringBuilder(array[0]);
    for (int i = 1; i < length; i++) {
      parentString.append("." + array[i]);
    }
    return parentString.toString();
  }

  /**
   * Assumes parent packages are already registered.
   * 
   * @param packageName
   * @throws NameResolutionException
   */
  public void registerPackage(String[] packageName)
      throws NameResolutionException {

    try {
      getNode(arrayToDotString(packageName, packageName.length));
      return;
    } catch (NameResolutionException e) {
    }

    final PackageNode parent;
    if (packageName.length == 1) {
      parent = namespace;
    } else {
      parent = (PackageNode) getNode(arrayToDotString(packageName,
          packageName.length - 1));
    }
    new PackageNode(parent, packageName[packageName.length - 1]);
  }
  public void register(Class<?> c) {
    if (c.getSuperclass() != null)
      register(c.getSuperclass());
    for (Class<?> i : c.getInterfaces()) {
      register(i);
    }
    
    List<Class<?>> pathToRoot = new ArrayList<Class<?>>();
    Class<?> d = c;
    do {
      pathToRoot.add(0, d);
    } while (null != (d = d.getEnclosingClass()));

    for (Class<?> p : pathToRoot) {
      Package pack = p.getPackage();
      final String packageName;
      if(pack == null) {
        String className = p.getName();
        int lastDot = className.lastIndexOf('.');
        if(lastDot != -1) {
          packageName = className.substring(0, lastDot);
        } else {
          packageName = "";
        }
      } else {
        packageName = pack.getName();
      }
      String[] packageList = packageName.split(regexp);
      packageList = Arrays.copyOf(packageList, packageList.length);

      for (int i = 0; i < packageList.length; i++) {
        try {
          registerPackage(Arrays.copyOf(packageList, i + 1));
        } catch (NameResolutionException e) {
          throw new IllegalStateException("Could not find parent package "
              + Arrays.toString(Arrays.copyOf(packageList, i + 1))
              + ", which this method should have registered.", e);
        }
      }
      // Now, register the class.
      registerClass(p);
      // Check for Namespace annotation, and register the namespace here (or at the end of registerClass)
    }
    for (Class<?> inner_class : c.getDeclaredClasses()) {
      register(inner_class);
    }
  }

  /**
   * Assumes that all of the parents of c have been registered already.
   * 
   * @param c
   */
  @SuppressWarnings("unchecked")
  private <T, U extends T> void registerClass(Class<U> c) {
    if (c.isArray()) {
      throw new UnsupportedOperationException("Can't register array types");
    }
    try {
      getNode(c);
      return;
    } catch (NameResolutionException e) {
    }

    // TODO: Constructor arguments can pull in classes.  The walk of
    // those dependencies belongs in register(), but is spread out below.
    Namespace nsAnnotation = c.getAnnotation(Namespace.class);
    Node n;
    if (nsAnnotation == null) {
      n = buildPathToNode(c, false);
    } else {
      n = buildPathToNode(c, true); 
      if(n instanceof NamedParameterNode) {
        throw new IllegalArgumentException("Found namespace annotation " +
            nsAnnotation + " with target " + n + " which is a named parameter.");
      }
      registerNamespace(nsAnnotation, (ClassNode<?>)n);
    }

    if(n instanceof ClassNode) {
      ClassNode<U> cn = (ClassNode<U>)n;
      Class<T> superclass = (Class<T>)c.getSuperclass();
      if (superclass != null) {
        try {
          putImpl((ClassNode<T>)getNode(superclass), cn);
        } catch (NameResolutionException e) {
          throw new IllegalStateException(e);
        }
      }
      for (Class<?> interf : c.getInterfaces()) {
        try {
          putImpl((ClassNode<T>) getNode(interf), cn);
        } catch (NameResolutionException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private <T, U extends T> void putImpl(ClassNode<T> superclass, ClassNode<U> impl) {
    List<ClassNode<?>> s = knownImpls.get(superclass);
    if (s == null) {
      s = new ArrayList<ClassNode<?>>();
      knownImpls.put(superclass, s);
    }
    if (!s.contains(impl)) {
      // System.out.println("putImpl: " + impl + " implements " + superclass);
      s.add(impl);
    }
  }

  @SuppressWarnings("unchecked")
  <T> ClassNode<T>[] getKnownImpls(ClassNode<T> c) {
    List<ClassNode<?>> l = knownImpls.get(c);
    if (l != null) {
      return (ClassNode<T>[])l.toArray(new ClassNode[0]);
    } else {
      return (ClassNode<T>[])new ClassNode[0];
    }
  }

  public String exportNamespace() {
    return namespace.toIndentedString(0);
  }

  public void findUnresolvedClasses(Node root, Set<Class<?>> unresolved) {
    if (root instanceof NamedParameterNode) {
      NamedParameterNode<?> np = (NamedParameterNode<?>) (root);
      try {
        getNode(np.argClass);
      } catch (NameResolutionException e) {
        unresolved.add(np.argClass);
      }
    } else if (root instanceof ClassNode) {
      ClassNode<?> cls = (ClassNode<?>) root;
      for (ConstructorDef def : cls.injectableConstructors) {
        for (ConstructorArg arg : def.args) {
          try {
            getNode(arg.type);
          } catch (NameResolutionException e) {
            unresolved.add(arg.type);
          }
        }
      }
/*      Class<?> zuper = cls.clazz.getSuperclass();
      if (zuper != null) {
        try {
          getNode(zuper);
        } catch (NameResolutionException e) {
          unresolved.add(zuper);
        }
      }
      Class<?>[] interfaces = cls.clazz.getInterfaces();
      for (Class<?> i : interfaces) {
        try {
          getNode(i);
        } catch (NameResolutionException e) {
          unresolved.add(i);
        }
      }*/
    }
    if (root.children != null) {
      for (Node n : root.children.values()) {
        findUnresolvedClasses(n, unresolved);
      }
    }
  }

  public Class<?>[] findUnresolvedClasses() {
    Set<Class<?>> unresolved = new HashSet<Class<?>>();
    findUnresolvedClasses(namespace, unresolved);
    return unresolved.toArray(new Class<?>[0]);
  }

  public void resolveAllClasses() {
    for (Class<?>[] classes = findUnresolvedClasses(); classes.length > 0; classes = findUnresolvedClasses()) {
      for (Class<?> c : classes) {
        register(c);
      }
    }
  }

  // TODO: Want to add a "register namespace" method, but Java is not designed
  // to support such things.
  // There are third party libraries that would help, but they can fail if the
  // relevant jar has not yet been loaded.

  public static void main(String[] args) throws Exception {
    TypeHierarchy ns = new TypeHierarchy();
    for (String s : args) {
      ns.register(ReflectionUtilities.classForName(s));
    }
    for (Class<?>[] classes = ns.findUnresolvedClasses(); classes.length > 0; classes = ns
        .findUnresolvedClasses()) {
      System.out.println("Found unresolved classes.  Loading them.");
      for (Class<?> c : classes) {
        System.out.println("  " + c.getName());
        ns.register(c);
      }
      System.out.println("Done.");
    }
    System.out.print(ns.exportNamespace());
    ns.writeJson(System.out);
  }

  public PackageNode getNamespace() {
    return namespace;
  }
  public Collection<NamedParameterNode<?>> getNamedParameterNodes() {
    return shortNames.values();
  }
  @SuppressWarnings("unchecked")
  public <T> NamedParameterNode<T> getNodeFromShortName(String shortName) {
    return (NamedParameterNode<T>)shortNames.get(shortName);
  }
}