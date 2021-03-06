/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.tang.implementation.java;

import com.microsoft.tang.*;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.exceptions.*;
import com.microsoft.tang.implementation.*;
import com.microsoft.tang.types.*;
import com.microsoft.tang.util.MonotonicHashSet;
import com.microsoft.tang.util.MonotonicSet;
import com.microsoft.tang.util.ReflectionUtilities;
import com.microsoft.tang.util.TracingMonotonicTreeMap;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class InjectorImpl implements Injector {
  final Map<ClassNode<?>, Object> instances = new TracingMonotonicTreeMap<>();
  final Map<NamedParameterNode<?>, Object> namedParameterInstances = new TracingMonotonicTreeMap<>();

  private boolean concurrentModificationGuard = false;

  private void assertNotConcurrent() {
    if (concurrentModificationGuard) {
      throw new ConcurrentModificationException("Detected attempt to use Injector from within an injected constructor!");
    }
  }

  private final Configuration c;
  private final ClassHierarchy namespace;
  private final JavaClassHierarchy javaNamespace;
  private final Set<InjectionFuture<?>> pendingFutures = new HashSet<>();
  private Aspect aspect;
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

    @Override
    protected String toAmbiguousInjectString() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected String toInfeasibleInjectString() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected boolean isInfeasibleLeaf() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toShallowString() {
      throw new UnsupportedOperationException();
    }

  };

  @SuppressWarnings("unchecked")
  private <T> T getCachedInstance(ClassNode<T> cn) {
    if (cn.getFullName().equals("com.microsoft.tang.Injector")) {
      return (T) this;// TODO: We should be insisting on injection futures here! .forkInjector();
    } else {
      T t = (T) instances.get(cn);
      if (t instanceof InjectionFuture) {
        throw new IllegalStateException("Found an injection future in getCachedInstance: " + cn);
      }
      return t;
    }
  }

  /**
   * Produce a list of "interesting" constructors from a set of ClassNodes.
   * <p/>
   * Tang Constructors expose a isMoreSpecificThan function that embeds all
   * a skyline query over the lattices.  Precisely:
   * <p/>
   * Let candidateConstructors be the union of all constructors defined by
   * ClassNodes in candidateImplementations.
   * <p/>
   * This function returns a set called filteredImplementations, defined as
   * follows:
   * <p/>
   * For each member f of filteredConstructors, there does not exist
   * a g in candidateConstructors s.t. g.isMoreSpecificThan(f).
   */
  private <T> List<InjectionPlan<T>> filterCandidateConstructors(
      List<ClassNode<T>> candidateImplementations,
      Map<Node, InjectionPlan<?>> memo) {

    List<InjectionPlan<T>> sub_ips = new ArrayList<>();
    for (ClassNode<T> thisCN : candidateImplementations) {
      final List<Constructor<T>> constructors = new ArrayList<>();
      final List<ConstructorDef<T>> constructorList = new ArrayList<>();
      if (null != c.getLegacyConstructor(thisCN)) {
        constructorList.add(c.getLegacyConstructor(thisCN));
      }
      constructorList
          .addAll(Arrays.asList(thisCN.getInjectableConstructors()));

      for (ConstructorDef<T> def : constructorList) {
        List<InjectionPlan<?>> args = new ArrayList<InjectionPlan<?>>();
        ConstructorArg[] defArgs = def.getArgs();

        for (ConstructorArg arg : defArgs) {
          if (!arg.isInjectionFuture()) {
            try {
              Node argNode = namespace.getNode(arg.getName());
              buildInjectionPlan(argNode, memo);
              args.add(memo.get(argNode));
            } catch (NameResolutionException e) {
              throw new IllegalStateException("Detected unresolvable "
                  + "constructor arg while building injection plan.  "
                  + "This should have been caught earlier!", e);
            }
          } else {
            try {
              args.add(new InjectionFuturePlan<>(namespace.getNode(arg
                  .getName())));
            } catch (NameResolutionException e) {
              throw new IllegalStateException("Detected unresolvable "
                  + "constructor arg while building injection plan.  "
                  + "This should have been caught earlier!", e);
            }
          }
        }
        Constructor<T> constructor = new Constructor<T>(thisCN, def,
            args.toArray(new InjectionPlan[0]));
        constructors.add(constructor);
      }
      // The constructors are embedded in a lattice defined by
      // isMoreSpecificThan().  We want to see if, amongst the injectable
      // plans, there is a unique dominant plan, and select it.

      // First, compute the set of injectable plans.
      List<Integer> liveIndices = new ArrayList<>();
      for (int i = 0; i < constructors.size(); i++) {
        if (constructors.get(i).getNumAlternatives() > 0) {
          liveIndices.add(i);
        }
      }
      // Now, do an all-by-all comparison, removing indices that are dominated
      // by others.
      for (int i = 0; i < liveIndices.size(); i++) {
        for (int j = i + 1; j < liveIndices.size(); j++) {
          ConstructorDef<T> ci = constructors.get(liveIndices.get(i))
              .getConstructorDef();
          ConstructorDef<T> cj = constructors.get(liveIndices.get(j))
              .getConstructorDef();

          if (ci.isMoreSpecificThan(cj)) {
            liveIndices.remove(j);
            j--;
          } else if (cj.isMoreSpecificThan(ci)) {
            liveIndices.remove(i);
            // Done with this inner loop invocation. Check the new ci.
            i--;
            break;
          }
        }
      }
      sub_ips.add(wrapInjectionPlans(thisCN, constructors, false,
          liveIndices.size() == 1 ? liveIndices.get(0) : -1));
    }
    return sub_ips;
  }

  @SuppressWarnings("unchecked")
  private <T> InjectionPlan<T> buildClassNodeInjectionPlan(ClassNode<T> cn,
                                                           T cachedInstance,
                                                           ClassNode<ExternalConstructor<T>> externalConstructor,
                                                           ClassNode<T> boundImpl,
                                                           ClassNode<T> defaultImpl,
                                                           Map<Node, InjectionPlan<?>> memo) {

    if (cachedInstance != null) {
      return new JavaInstance<T>(cn, cachedInstance);
    } else if (externalConstructor != null) {
      buildInjectionPlan(externalConstructor, memo);
      return new Subplan<>(cn, 0, (InjectionPlan<T>) memo.get(externalConstructor));
    } else if (boundImpl != null && !cn.equals(boundImpl)) {
      // We need to delegate to boundImpl, so recurse.
      buildInjectionPlan(boundImpl, memo);
      return new Subplan<>(cn, 0, (InjectionPlan<T>) memo.get(boundImpl));
    } else if (defaultImpl != null && !cn.equals(defaultImpl)) {
      buildInjectionPlan(defaultImpl, memo);
      return new Subplan<>(cn, 0, (InjectionPlan<T>) memo.get(defaultImpl));
    } else {
      // if we're here and there is a bound impl or a default impl,
      // then we're bound / defaulted to ourselves, so don't add
      // other impls to the list of things to consider.
      List<ClassNode<T>> candidateImplementations = new ArrayList<>();
      if (boundImpl == null && defaultImpl == null) {
        candidateImplementations.addAll(cn.getKnownImplementations());
      }
      candidateImplementations.add(cn);
      List<InjectionPlan<T>> sub_ips = filterCandidateConstructors(candidateImplementations, memo);
      if (candidateImplementations.size() == 1
          && candidateImplementations.get(0).getFullName()
          .equals(cn.getFullName())) {
        return wrapInjectionPlans(cn, sub_ips, false, -1);
      } else {
        return wrapInjectionPlans(cn, sub_ips, true, -1);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> InjectionPlan<T> wrapInjectionPlans(ClassNode<T> infeasibleNode,
                                                  List<? extends InjectionPlan<T>> list, boolean forceAmbiguous, int selectedIndex) {
    if (list.size() == 0) {
      return new Subplan<>(infeasibleNode);
    } else if ((!forceAmbiguous) && list.size() == 1) {
      return list.get(0);
    } else {
      return new Subplan<>(infeasibleNode, selectedIndex, list.toArray(new InjectionPlan[0]));
    }
  }

  /**
   * Parse the bound value of np.  When possible, this returns a cached instance.
   *
   * @return null if np has not been bound.
   * @throws ParseException
   */
  @SuppressWarnings("unchecked")
  private <T> T parseBoundNamedParameter(NamedParameterNode<T> np) {
    final T ret;

    @SuppressWarnings("rawtypes")
    final Set<Object> boundSet = c.getBoundSet((NamedParameterNode) np);
    if (!boundSet.isEmpty()) {
      Set<T> ret2 = new MonotonicSet<>();
      for (Object o : boundSet) {
        if (o instanceof String) {
          try {
            ret2.add(javaNamespace.parse(np, (String) o));
          } catch (ParseException e) {
            // Parsability is now pre-checked in bindSet, so it should not be reached!
            throw new IllegalStateException("Could not parse " + o + " which was passed into " + np + " FIXME: Parsability is not currently checked by bindSetEntry(Node,String)");
          }
        } else if (o instanceof Node) {
          ret2.add((T) o);
        } else {
          throw new IllegalStateException("Unexpected object " + o + " in bound set.  Should consist of nodes and strings");
        }
      }
      return (T) ret2;
    }
    final List<Object> boundList = c.getBoundList((NamedParameterNode) np);
    if (boundList != null) {
      List<T> ret2 = new ArrayList<>();
      for (Object o: boundList) {
        if (o instanceof String) {
          try {
            ret2.add(javaNamespace.parse(np, (String) o));
          } catch (ParseException e) {
            // Parsability is now pre-checked in bindList, so it should not be reached!
            throw new IllegalStateException("Could not parse " + o + " which was passed into " + np + " FIXME: " +
                "Parsability is not currently checked by bindList(Node,List)");
          }
        } else if (o instanceof Node) {
          ret2.add((T) o);
        } else {
          throw new IllegalStateException("Unexpected object " + o + " in bound list.  Should consist of nodes and " +
              "strings");
        }
      }
      return (T) ret2;
    }
    else if (namedParameterInstances.containsKey(np)) {
      ret = (T) namedParameterInstances.get(np);
    } else {
      final String value = c.getNamedParameter(np);
      if (value == null) {
        ret = null;
      } else {
        try {
          ret = javaNamespace.parse(np, value);
          namedParameterInstances.put(np, ret);
        } catch (BindException e) {
          throw new IllegalStateException(
              "Could not parse pre-validated value", e);
        }
      }
    }
    return ret;
  }

  @SuppressWarnings("unchecked")
  private <T> ClassNode<T> parseDefaultImplementation(ClassNode<T> cn) {
    if (cn.getDefaultImplementation() != null) {
      try {
        return (ClassNode<T>) javaNamespace.getNode(cn.getDefaultImplementation());
      } catch (ClassCastException | NameResolutionException e) {
        throw new IllegalStateException("After validation, " + cn + " had a bad default implementation named " + cn.getDefaultImplementation(), e);
      }
    } else {
      return null;
    }
  }

  @SuppressWarnings({"unchecked"})
  private <T> void buildInjectionPlan(final Node n,
                                      Map<Node, InjectionPlan<?>> memo) {
    if (memo.containsKey(n)) {
      if (BUILDING == memo.get(n)) {
        StringBuilder loopyList = new StringBuilder("[");
        for (Node node : memo.keySet()) {
          if (memo.get(node) == BUILDING) {
            loopyList.append(" " + node.getFullName());
          }
        }
        loopyList.append(" ]");
        throw new ClassHierarchyException("Detected loopy constructor involving "
            + loopyList.toString());
      } else {
        return;
      }
    }
    memo.put(n, BUILDING);
    final InjectionPlan<T> ip;
    if (n instanceof NamedParameterNode) {
      final NamedParameterNode<T> np = (NamedParameterNode<T>) n;

      final T boundInstance = parseBoundNamedParameter(np);
      final T defaultInstance = javaNamespace.parseDefaultValue(np);
      final T instance = boundInstance != null ? boundInstance : defaultInstance;

      if (instance instanceof Node) {
        buildInjectionPlan((Node) instance, memo);
        ip = new Subplan<T>(n, 0, (InjectionPlan<T>) memo.get(instance));
      } else if (instance instanceof Set) {
        Set<T> entries = (Set<T>) instance;
        Set<InjectionPlan<T>> plans = new MonotonicHashSet<>();
        for (T entry : entries) {
          if (entry instanceof ClassNode) {
            buildInjectionPlan((ClassNode<?>) entry, memo);
            plans.add((InjectionPlan<T>) memo.get(entry));
          } else {
            plans.add(new JavaInstance<T>(n, entry));
          }

        }
        ip = new SetInjectionPlan<T>(n, plans);
      } else if (instance instanceof List) {
        List<T> entries = (List<T>) instance;
        List<InjectionPlan<T>> plans = new ArrayList<>();
        for (T entry : entries) {
          if (entry instanceof ClassNode) {
            buildInjectionPlan((ClassNode<?>) entry, memo);
            plans.add((InjectionPlan<T>) memo.get(entry));
          } else {
            plans.add(new JavaInstance<T>(n, entry));
          }
        }
        ip = new ListInjectionPlan<T>(n, plans);
      } else {
        ip = new JavaInstance<T>(np, instance);
      }
    } else if (n instanceof ClassNode) {
      final ClassNode<T> cn = (ClassNode<T>) n;

      // Any (or all) of the next four values might be null; that's fine.
      final T cached = getCachedInstance(cn);
      final ClassNode<T> boundImpl = c.getBoundImplementation(cn);
      final ClassNode<T> defaultImpl = parseDefaultImplementation(cn);
      final ClassNode<ExternalConstructor<T>> ec = c.getBoundConstructor(cn);

      ip = buildClassNodeInjectionPlan(cn, cached, ec, boundImpl, defaultImpl, memo);
    } else if (n instanceof PackageNode) {
      throw new IllegalArgumentException(
          "Request to instantiate Java package as object");
    } else {
      throw new IllegalStateException(
          "Type hierarchy contained unknown node type!:" + n);
    }
    memo.put(n, ip);
  }

  /**
   * Return an injection plan for the given class / parameter name.
   *
   * @param name The name of an injectable class or interface, or a NamedParameter.
   * @return
   * @throws NameResolutionException
   */
  public InjectionPlan<?> getInjectionPlan(final Node n) {
    Map<Node, InjectionPlan<?>> memo = new HashMap<>();
    buildInjectionPlan(n, memo);
    return memo.get(n);
  }

  @Override
  public InjectionPlan<?> getInjectionPlan(String name) throws NameResolutionException {
    return getInjectionPlan(namespace.getNode(name));
  }

  @SuppressWarnings("unchecked")
  public <T> InjectionPlan<T> getInjectionPlan(Class<T> name) {
    return (InjectionPlan<T>) getInjectionPlan(javaNamespace.getNode(name));
  }

  @Override
  public boolean isInjectable(String name) throws NameResolutionException {
    return getInjectionPlan(namespace.getNode(name)).isInjectable();
  }

  @Override
  public boolean isInjectable(Class<?> clazz) {
    try {
      return isInjectable(ReflectionUtilities.getFullName(clazz));
    } catch (NameResolutionException e) {
      throw new IllegalStateException("Could not round trip " + clazz + " through ClassHierarchy", e);
    }
  }

  @Override
  public boolean isParameterSet(String name) throws NameResolutionException {
    InjectionPlan<?> p = getInjectionPlan(namespace.getNode(name));
    return p.isInjectable();
  }

  @Override
  public boolean isParameterSet(Class<? extends Name<?>> name)
      throws BindException {
    return isParameterSet(name.getName());
  }

  public InjectorImpl(Configuration c) throws BindException {
    this.c = c;
    this.namespace = c.getClassHierarchy();
    this.javaNamespace = (ClassHierarchyImpl) this.namespace;
  }

  private <U> U getInstance(Node n) throws InjectionException {
    assertNotConcurrent();
    @SuppressWarnings("unchecked")
    InjectionPlan<U> plan = (InjectionPlan<U>) getInjectionPlan(n);
    U u = (U) injectFromPlan(plan);

    while (!pendingFutures.isEmpty()) {
      Iterator<InjectionFuture<?>> i = pendingFutures.iterator();
      InjectionFuture<?> f = i.next();
      pendingFutures.remove(f);
      f.get();
    }
    return u;
  }

  @Override
  public <U> U getInstance(Class<U> clazz) throws InjectionException {
    if (Name.class.isAssignableFrom(clazz)) {
      throw new InjectionException("getInstance() called on Name "
          + ReflectionUtilities.getFullName(clazz)
          + " Did you mean to call getNamedInstance() instead?");
    }
    return getInstance(javaNamespace.getNode(clazz));
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> U getInstance(String clazz) throws InjectionException, NameResolutionException {
    return (U) getInstance(namespace.getNode(clazz));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getNamedInstance(Class<? extends Name<T>> clazz)
      throws InjectionException {
    return (T) getInstance(javaNamespace.getNode(clazz));
  }

  public <T> T getNamedParameter(Class<? extends Name<T>> clazz)
      throws InjectionException {
    return getNamedInstance(clazz);
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
      if (args[i].isInjectionFuture()) {
        parameterTypes[i] = InjectionFuture.class;
      } else {
        parameterTypes[i] = javaNamespace.classForName(args[i].getType());
      }
    }
    java.lang.reflect.Constructor<T> cons = clazz
        .getDeclaredConstructor(parameterTypes);
    cons.setAccessible(true);
    return cons;
  }

  /**
   * This gets really nasty now that constructors can invoke operations on us.
   * The upshot is that we should check to see if instances have been
   * registered by callees after each recursive invocation of injectFromPlan or
   * constructor invocations. The error handling currently bails if the thing we
   * just instantiated should be discarded.
   * <p/>
   * This could happen if (for instance), a constructor did a
   * bindVolatileInstance of its own class to an instance, or somehow triggered
   * an injection of itself with a different plan (an injection of itself with
   * the same plan would lead to an infinite recursion, so it's not really our
   * problem).
   *
   * @param plan
   * @return
   * @throws InjectionException
   */
  @SuppressWarnings("unchecked")
  private <T> T injectFromPlan(InjectionPlan<T> plan) throws InjectionException {

    if (!plan.isFeasible()) {
      throw new InjectionException("Cannot inject " + plan.getNode().getFullName() + ": "
          + plan.toCantInjectString());
    }
    if (plan.isAmbiguous()) {
      throw new InjectionException("Cannot inject " + plan.getNode().getFullName() + " "
          + plan.toCantInjectString());
    }
    if (plan instanceof InjectionFuturePlan) {
      InjectionFuturePlan<T> fut = (InjectionFuturePlan<T>) plan;
      final String key = fut.getNode().getFullName();
      try {
        InjectionFuture<?> ret = new InjectionFuture<>(this, javaNamespace.classForName(fut.getNode().getFullName()));
        pendingFutures.add(ret);
        return (T) ret;
      } catch (ClassNotFoundException e) {
        throw new InjectionException("Could not get class for " + key);
      }
    } else if (plan.getNode() instanceof ClassNode && null != getCachedInstance((ClassNode<T>) plan.getNode())) {
      return getCachedInstance((ClassNode<T>) plan.getNode());
    } else if (plan instanceof JavaInstance) {
      // TODO: Must be named parameter node.  Check.
//      throw new IllegalStateException("Instance from plan not in Injector's set of instances?!?");
      return ((JavaInstance<T>) plan).instance;
    } else if (plan instanceof Constructor) {
      final Constructor<T> constructor = (Constructor<T>) plan;
      final Object[] args = new Object[constructor.getArgs().length];
      final InjectionPlan<?>[] argPlans = constructor.getArgs();

      for (int i = 0; i < argPlans.length; i++) {
        args[i] = injectFromPlan(argPlans[i]);
      }
      try {
        concurrentModificationGuard = true;
        T ret;
        try {
          ConstructorDef<T> def = (ConstructorDef<T>) constructor.getConstructorDef();
          java.lang.reflect.Constructor<T> c = getConstructor(def);

          if (aspect != null) {
            ret = aspect.inject(def, c, args);
          } else {
            ret = c.newInstance(args);
          }
        } catch (IllegalArgumentException e) {
          StringBuilder sb = new StringBuilder("Internal Tang error?  Could not call constructor " + constructor.getConstructorDef() + " with arguments [");
          for (Object o : args) {
            sb.append("\n\t" + o);
          }
          sb.append("]");
          throw new IllegalStateException(sb.toString(), e);
        }
        if (ret instanceof ExternalConstructor) {
          ret = ((ExternalConstructor<T>) ret).newInstance();
        }
        instances.put(constructor.getNode(), ret);
        return ret;
      } catch (ReflectiveOperationException e) {
        throw new InjectionException("Could not invoke constructor: " + plan, e instanceof InvocationTargetException ? e.getCause() : e);
      } finally {
        concurrentModificationGuard = false;
      }
    } else if (plan instanceof Subplan) {
      Subplan<T> ambiguous = (Subplan<T>) plan;
      return injectFromPlan(ambiguous.getDelegatedPlan());
    } else if (plan instanceof SetInjectionPlan) {
      SetInjectionPlan<T> setPlan = (SetInjectionPlan<T>) plan;
      Set<T> ret = new MonotonicHashSet<>();
      for (InjectionPlan<T> subplan : setPlan.getEntryPlans()) {
        ret.add(injectFromPlan(subplan));
      }
      return (T) ret;
    } else if (plan instanceof ListInjectionPlan) {
      ListInjectionPlan<T> listPlan = (ListInjectionPlan<T>) plan;
      List<T> ret = new ArrayList<>();
      for (InjectionPlan<T> subplan : listPlan.getEntryPlans()) {
        ret.add(injectFromPlan(subplan));
      }
      return (T) ret;
    } else {
      throw new IllegalStateException("Unknown plan type: " + plan);
    }
  }

  private static InjectorImpl copy(InjectorImpl old,
                                   Configuration... configurations) throws BindException {
    final InjectorImpl i;
    try {
      final ConfigurationBuilder cb = old.c.newBuilder();
      for (Configuration c : configurations) {
        cb.addConfiguration(c);
      }
      i = new InjectorImpl(cb.build());
    } catch (BindException e) {
      throw new IllegalStateException(
          "Unexpected error copying configuration!", e);
    }
    for (ClassNode<?> cn : old.instances.keySet()) {
      if (cn.getFullName().equals(ReflectionUtilities.getFullName(Injector.class))
          || cn.getFullName().equals(ReflectionUtilities.getFullName(InjectorImpl.class))) {
        // This would imply that we're treating injector as a singleton somewhere.  It should be copied fresh each time.
        throw new IllegalStateException();
      }
      try {
        ClassNode<?> new_cn = (ClassNode<?>) i.namespace.getNode(cn
            .getFullName());
        i.instances.put(new_cn, old.instances.get(cn));
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
          .getNode(np.getFullName());
      i.namedParameterInstances.put(new_np, o);
    }
    // Fork the aspect (if any)
    if (old.aspect != null) {
      i.bindAspect(old.aspect.createChildAspect());
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
    assertNotConcurrent();
    Node n = javaNamespace.getNode(c);
    if (n instanceof ClassNode) {
      ClassNode<?> cn = (ClassNode<?>) n;
      Object old = getCachedInstance(cn);
      if (old != null) {
        throw new BindException("Attempt to re-bind instance.  Old value was "
            + old + " new value is " + o);
      }
      instances.put(cn, o);
    } else {
      throw new IllegalArgumentException("Expected Class but got " + c
          + " (probably a named parameter).");
    }
  }

  <T> void bindVolatileParameterNoCopy(Class<? extends Name<T>> c, T o)
      throws BindException {
    Node n = javaNamespace.getNode(c);
    if (n instanceof NamedParameterNode) {
      NamedParameterNode<?> np = (NamedParameterNode<?>) n;
      Object old = this.c.getNamedParameter(np);
      if (old != null) {
        // XXX need to get the binding site here!
        throw new BindException(
            "Attempt to re-bind named parameter " + ReflectionUtilities.getFullName(c) + ".  Old value was [" + old
                + "] new value is [" + o + "]");
      }
      try {
        namedParameterInstances.put(np, o);
      } catch (IllegalArgumentException e) {
        throw new BindException(
            "Attempt to re-bind named parameter " + ReflectionUtilities.getFullName(c) + ".  Old value was [" + old
                + "] new value is [" + o + "]");

      }
    } else {
      throw new IllegalArgumentException("Expected Name, got " + c
          + " (probably a class)");
    }
  }

  @Override
  public Injector forkInjector() {
    try {
      return forkInjector(new Configuration[0]);
    } catch (BindException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Injector forkInjector(Configuration... configurations)
      throws BindException {
    InjectorImpl ret;
    ret = copy(this, configurations);
    return ret;
  }

  @Override
  public <T> void bindAspect(Aspect a) throws BindException {
    if (aspect != null) {
      throw new BindException("Attempt to re-bind aspect! old=" + aspect + " new=" + a);
    }
    aspect = a;
  }

  @Override
  public Aspect getAspect() {
    return aspect;
  }
}