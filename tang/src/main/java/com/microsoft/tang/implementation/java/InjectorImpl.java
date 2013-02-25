package com.microsoft.tang.implementation.java;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.tang.ClassHierarchy;
import com.microsoft.tang.ClassNode;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.JavaConfigurationBuilder;
import com.microsoft.tang.ConstructorArg;
import com.microsoft.tang.ConstructorDef;
import com.microsoft.tang.ExternalConstructor;
import com.microsoft.tang.Injector;
import com.microsoft.tang.NamedParameterNode;
import com.microsoft.tang.NamespaceNode;
import com.microsoft.tang.Node;
import com.microsoft.tang.PackageNode;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.tang.exceptions.InjectionException;
import com.microsoft.tang.exceptions.NameResolutionException;
import com.microsoft.tang.implementation.Constructor;
import com.microsoft.tang.implementation.InjectionPlan;
import com.microsoft.tang.implementation.Subplan;
import com.microsoft.tang.util.MonotonicMap;
import com.microsoft.tang.util.ReflectionUtilities;

public class InjectorImpl implements Injector {

  final Map<ClassNode<?>, Object> singletonInstances = new MonotonicMap<>();
  final Map<NamedParameterNode<?>, Object> namedParameterInstances = new MonotonicMap<>();

  private class SingletonInjectionException extends InjectionException {
    private static final long serialVersionUID = 1L;

    SingletonInjectionException(String s) {
      super(s);
    }
  }

  final JavaConfigurationBuilder cb;
  final ConfigurationBuilderImpl cbi;
  final ClassHierarchy namespace;
  final ClassHierarchyImpl javaNamespace;
  static final InjectionPlan<?> BUILDING = new InjectionPlan<Object>(null) {
    @Override
    public int getNumAlternatives() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return "BUILDING INJECTION PLAN";
    }

    @Override
    public boolean isAmbiguous() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInjectable() {
      throw new UnsupportedOperationException();
    }
  };

  @SuppressWarnings("unchecked")
  private InjectionPlan<?> wrapInjectionPlans(Node infeasibleNode,
      List<InjectionPlan<?>> list, boolean forceAmbiguous) {
    if (list.size() == 0) {
      return new Subplan<>(infeasibleNode);
    } else if ((!forceAmbiguous) && list.size() == 1) {
      return list.get(0);
    } else {
      return new Subplan<>(infeasibleNode, list.toArray(new InjectionPlan[0]));
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void buildInjectionPlan(final String name,
      Map<String, InjectionPlan<?>> memo) throws InjectionException {
    if (memo.containsKey(name)) {
      if (BUILDING == memo.get(name)) {
        throw new IllegalStateException("Detected loopy constructor involving "
            + name);
      } else {
        return;
      }
    }
    memo.put(name, BUILDING);
    final Node n;
    try {
      n = javaNamespace.register(name);
    } catch (BindException e) {
      throw new IllegalArgumentException("Could not register class " + name, e);
    }
    final InjectionPlan<?> ip;
    if (n instanceof NamedParameterNode) {
      NamedParameterNode<?> np = (NamedParameterNode<?>) n;
      Object instance = namedParameterInstances.get(n);
      if (instance == null) {
        String value = cbi.namedParameters.get(n);
        if(value != null) {
          try {
            instance = cbi.parse(np, value);
          } catch (BindException e) {
            throw new IllegalStateException("Could not parse pre-validated value", e);
          }
          namedParameterInstances.put(np, instance);
          if (instance instanceof Class) {
            try {
              cbi.register((Class<?>) instance);
            } catch (BindException e) {
              throw new IllegalStateException("Could not register class " + instance
                  + " which should have already been registered!");
            }
          }
        } else {
          instance = cbi.parseDefaultValue(np);
        }
      }
      if (instance instanceof Class) {
        String implName = ((Class) instance).getName();
        buildInjectionPlan(implName, memo);
        ip = new Subplan<>(np, 0, memo.get(implName));
      } else {
        ip = new JavaInstance<Object>(np, instance);
      }
    } else if (n instanceof ClassNode) {
      ClassNode<?> cn = (ClassNode<?>) n;
      if (singletonInstances.containsKey(cn)) {
        ip = new JavaInstance<Object>(cn, singletonInstances.get(cn));
      } else if (cbi.boundConstructors.containsKey(cn)) {
        String constructorName = cbi.boundConstructors.get(cn).getFullName();
        buildInjectionPlan(constructorName, memo);
        ip = new Subplan(cn, 0, memo.get(constructorName));
        memo.put(cn.getFullName(), ip);
        // ip = new Instance(cn, null);
      } else if (cbi.boundImpls.containsKey(cn)
          && !(cbi.boundImpls.get(cn).getFullName().equals(cn.getFullName()))) {
        String implName = cbi.boundImpls.get(cn).getFullName();
        buildInjectionPlan(implName, memo);
        ip = new Subplan(cn, 0, memo.get(implName));
        memo.put(cn.getFullName(), ip);
      } else {
        List<ClassNode<?>> classNodes = new ArrayList<>();
        // if we're here and there is a bound impl, then we're bound to
        // ourselves, so don't add known impls to the list of things to
        // consider.
        if (cbi.boundImpls.get(cn) == null) {
          classNodes.addAll(cn.getKnownImplementations());
        }
        classNodes.add(cn);
        List<InjectionPlan<?>> sub_ips = new ArrayList<InjectionPlan<?>>();
        for (ClassNode<?> thisCN : classNodes) {
          final List<InjectionPlan<?>> constructors = new ArrayList<InjectionPlan<?>>();
          final List<ConstructorDef<?>> constructorList = new ArrayList<>();
          if (cbi.legacyConstructors.containsKey(thisCN)) {
            constructorList.add(cbi.legacyConstructors.get(thisCN));
          }
          constructorList.addAll(Arrays.asList(thisCN
              .getInjectableConstructors()));

          for (ConstructorDef<?> def : constructorList) {
            List<InjectionPlan<?>> args = new ArrayList<InjectionPlan<?>>();
            for (ConstructorArg arg : def.getArgs()) {
              String argName = arg.getName(); // getFullyQualifiedName(thisCN.clazz);
              buildInjectionPlan(argName, memo);
              args.add(memo.get(argName));
            }
            Constructor constructor = new Constructor(thisCN, def,
                args.toArray(new InjectionPlan[0]));
            constructors.add(constructor);
          }
          sub_ips.add(wrapInjectionPlans(thisCN, constructors, false));
        }
        if (classNodes.size() == 1 && classNodes.get(0).getFullName()
        /* getClazz().getName() */.equals(name)) {
          ip = wrapInjectionPlans(n, sub_ips, false);
        } else {
          ip = wrapInjectionPlans(n, sub_ips, true);
        }
      }
    } else if (n instanceof PackageNode) {
      throw new IllegalArgumentException(
          "Request to instantiate Java package as object");
    } else if (n instanceof NamespaceNode) {
      throw new IllegalArgumentException(
          "Request to instantiate ConfigurationBuilderImpl namespace as object");
    } else {
      throw new IllegalStateException(
          "Type hierarchy contained unknown node type!:" + n);
    }
    memo.put(name, ip);
  }

  /**
   * Return an injection plan for the given class / parameter name. This will be
   * more useful once plans can be serialized / deserialized / pretty printed.
   * 
   * @param name
   *          The name of an injectable class or interface, or a NamedParameter.
   * @return
   * @throws NameResolutionException
   */
  public InjectionPlan<?> getInjectionPlan(String name) throws InjectionException {
    Map<String, InjectionPlan<?>> memo = new HashMap<String, InjectionPlan<?>>();
    buildInjectionPlan(name, memo);
    return memo.get(name);
  }

  @SuppressWarnings("unchecked")
  public <T> InjectionPlan<T> getInjectionPlan(Class<T> name) throws InjectionException {
    return (InjectionPlan<T>) getInjectionPlan(name.getName());
  }

  @Override
  public boolean isInjectable(String name) throws BindException {
    try {
      InjectionPlan<?> p = getInjectionPlan(name);
      return p.isInjectable();
    } catch(InjectionException e) {
      throw (BindException)e.getCause();
    }
  }

  @Override
  public boolean isInjectable(Class<?> clazz) throws BindException {
    return isInjectable(clazz.getName());
  }

  @Override
  public boolean isParameterSet(String name) throws BindException {
    try {
      InjectionPlan<?> p = getInjectionPlan(name);
      return p.isInjectable();
    } catch(InjectionException e) {
      throw (BindException)e.getCause();
    }
  }

  @Override
  public boolean isParameterSet(Class<? extends Name<?>> name)
      throws BindException {
    return isParameterSet(name.getName());
  }

  public InjectorImpl(ConfigurationImpl c) throws BindException {
    this.cb = c.builder;
    this.cbi = c.builder;
    this.namespace = c.builder.namespace;
    this.javaNamespace = c.builder.namespace;
  }

  boolean populated = false;

  private void populateSingletons() throws InjectionException {
    if (!populated) {
      populated = true;
      boolean stillHope = true;
      boolean allSucceeded = false;
      while (!allSucceeded) {
        boolean oneSucceeded = false;
        allSucceeded = true;
        for (ClassNode<?> cn : cbi.singletons) {
          if (!singletonInstances.containsKey(cn)) {
            try {
              getInstance(cn.getFullName());// getClazz());
              // System.err.println("success " + cn);
              oneSucceeded = true;
            } catch (SingletonInjectionException e) {
              // System.err.println("failure " + cn);
              allSucceeded = false;
              if (!stillHope) {
                throw e;
              }
            }
          }
        }
        if (!oneSucceeded) {
          stillHope = false;
        }
      }
    }
  }

  @Override
  public <U> U getInstance(Class<U> clazz) throws InjectionException {
    populateSingletons();
    InjectionPlan<U> plan = getInjectionPlan(clazz);
    return injectFromPlan(plan);
  }

  @Override
  public <U> U getNamedInstance(Class<? extends Name<U>> clazz)
      throws InjectionException {
    populateSingletons();
    @SuppressWarnings("unchecked")
    InjectionPlan<U> plan = (InjectionPlan<U>) getInjectionPlan(clazz.getName());
    return injectFromPlan(plan);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> U getInstance(String clazz) throws InjectionException {
    populateSingletons();
    InjectionPlan<?> plan = getInjectionPlan(clazz);
    return (U) injectFromPlan(plan);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getNamedParameter(Class<? extends Name<T>> clazz)
      throws InjectionException {
    InjectionPlan<T> plan = (InjectionPlan<T>) getInjectionPlan(clazz.getName());
    return (T) injectFromPlan(plan);
  }

  private <T> java.lang.reflect.Constructor<T> getConstructor(
      ConstructorDef<T> constructor) throws ClassNotFoundException,
      NoSuchMethodException, SecurityException {
    @SuppressWarnings("unchecked")
    Class<T> clazz = (Class<T>) javaNamespace.classForName(constructor
        .getClassName());
    ConstructorArg[] args = constructor.getArgs();
    Class<?> parameterTypes[] = new Class[args.length];
    for (int i = 0; i < args.length; i++) {
      parameterTypes[i] = javaNamespace.classForName(args[i].getType());
    }
    java.lang.reflect.Constructor<T> cons = clazz
        .getDeclaredConstructor(parameterTypes);
    cons.setAccessible(true);
    return cons;
  }

  @SuppressWarnings("unchecked")
  <T> T injectFromPlan(InjectionPlan<T> plan) throws InjectionException {
    if (!plan.isFeasible()) {
      throw new InjectionException("Attempt to inject infeasible plan: "
          + plan.toPrettyString());
    }
    if (plan.isAmbiguous()) {
      throw new IllegalArgumentException("Attempt to inject ambiguous plan: "
          + plan.toPrettyString());
    }
    if (plan instanceof JavaInstance) {
      return ((JavaInstance<T>) plan).instance;
    } else if (plan instanceof Constructor) {
      Constructor<T> constructor = (Constructor<T>) plan;
      if (singletonInstances.containsKey(constructor.getNode())) {
        throw new SingletonInjectionException(
            "Attempt to re-instantiate singleton: " + constructor.getNode());
      }
      Object[] args = new Object[constructor.getArgs().length];
      for (int i = 0; i < constructor.getArgs().length; i++) {
        args[i] = injectFromPlan(constructor.getArgs()[i]);
      }
      try {
        T ret = getConstructor(
            (ConstructorDef<T>) constructor.getConstructorDef()).newInstance(
            args);
        if (cbi.singletons.contains(constructor.getNode())) {
          singletonInstances.put(constructor.getNode(), ret);
        }
        // System.err.println("returning a new " + constructor.getNode());
        return ret;
      } catch (ReflectiveOperationException e) {
        throw new InjectionException("Could not invoke constructor", e);
      }
    } else if (plan instanceof Subplan) {
      Subplan<T> ambiguous = (Subplan<T>) plan;
      if (ambiguous.isInjectable()) {
        if (singletonInstances.containsKey(ambiguous.getNode())) {
          throw new SingletonInjectionException(
              "Attempt to re-instantiate singleton: " + ambiguous.getNode());
        }
        Object ret = injectFromPlan(ambiguous.getDelegatedPlan());
        if (cbi.singletons.contains(ambiguous.getNode())) {
          // Cast is safe since singletons is of type Set<ClassNode<?>>
          singletonInstances.put((ClassNode<?>) ambiguous.getNode(), ret);
        }
        // TODO: Check "T" in "instanceof ExternalConstructor<T>"
        if (ret instanceof ExternalConstructor) {
          // TODO fix up generic types for injectFromPlan with external
          // constructor!
          return ((ExternalConstructor<T>) ret).newInstance();
        } else {
          return (T) ret;
        }
      } else {
        if (ambiguous.getNumAlternatives() == 0) {
          throw new InjectionException("Attempt to inject infeasible plan:"
              + plan.toPrettyString());
        } else {
          throw new InjectionException("Attempt to inject ambiguous plan:"
              + plan.toPrettyString());
        }
      }
    } else {
      throw new IllegalStateException("Unknown plan type: " + plan);
    }
  }

  private static InjectorImpl copy(InjectorImpl old,
      Configuration... configurations) throws BindException {
    final InjectorImpl i;
    try {
      final ConfigurationBuilderImpl cb = new ConfigurationBuilderImpl(old.cbi);
      for (Configuration c : configurations) {
        cb.addConfiguration(c);
      }
      i = new InjectorImpl(cb.build());
    } catch (BindException e) {
      throw new IllegalStateException(
          "Unexpected error copying configuration!", e);
    }
    for (ClassNode<?> cn : old.singletonInstances.keySet()) {
      try {
        ClassNode<?> new_cn = (ClassNode<?>) i.namespace.register(cn
            .getFullName());
        i.singletonInstances.put(new_cn, old.singletonInstances.get(cn));
      } catch (BindException e) {
        throw new IllegalStateException("Could not resolve name "
            + cn.getFullName() + " when copying injector");
      }
    }
    // Copy references to the remaining (which must have been set with
    // bindVolatileParameter())
    for (NamedParameterNode<?> np : old.namedParameterInstances.keySet()) {
      // if (!builder.namedParameters.containsKey(np)) {
      Object o = old.namedParameterInstances.get(np);
      NamedParameterNode<?> new_np = (NamedParameterNode<?>) i.namespace
          .register(np.getFullName());
      i.namedParameterInstances.put(new_np, o);
      if (o instanceof Class) {
        i.namespace.register(ReflectionUtilities.getFullName((Class<?>) o));
      }
    }
    return i;
  }

  @Override
  public <T> void bindVolatileInstance(Class<T> c, T o) throws BindException {
    bindVolatileInstanceNoCopy(c, o);
  }

  @Override
  public <T> void bindVolatileParameter(Class<? extends Name<T>> c, T o)
      throws BindException {
    bindVolatileParameterNoCopy(c, o);
  }

  <T> void bindVolatileInstanceNoCopy(Class<T> c, T o) throws BindException {
    Node n = namespace.register(ReflectionUtilities.getFullName(c));
    /*
     * try { n = tc.namespace.getNode(c); } catch (NameResolutionException e) {
     * // TODO: Unit test for bindVolatileInstance to unknown class. throw new
     * BindException("Can't bind to unknown class " + c.getName(), e); }
     */

    if (n instanceof ClassNode) {
      ClassNode<?> cn = (ClassNode<?>) n;
      Object old = singletonInstances.get(cn);
      if (old != null) {
        throw new BindException("Attempt to re-bind singleton.  Old value was "
            + old + " new value is " + o);
      }
      singletonInstances.put(cn, o);
    } else {
      throw new IllegalArgumentException("Expected Class but got " + c
          + " (probably a named parameter).");
    }
  }

  <T> void bindVolatileParameterNoCopy(Class<? extends Name<T>> c, T o)
      throws BindException {
    Node n = namespace.register(ReflectionUtilities.getFullName(c));
    if (n instanceof NamedParameterNode) {
      NamedParameterNode<?> np = (NamedParameterNode<?>) n;
      Object old = cbi.namedParameters.get(np);
      if(old == null) {
        old = namedParameterInstances.get(np);
      }
      if (old != null) {
        throw new BindException(
            "Attempt to re-bind named parameter.  Old value was " + old
                + " new value is " + o);
      }
      namedParameterInstances.put(np, o);
      if (o instanceof Class) {
        namespace.register(ReflectionUtilities.getFullName((Class<?>) o));
      }
    } else {
      throw new IllegalArgumentException("Expected Name, got " + c
          + " (probably a class)");
    }
  }

  @Override
  public Injector createChildInjector(Configuration... configurations)
      throws BindException {
    InjectorImpl ret;
    ret = copy(this, configurations);
    return ret;
  }

}