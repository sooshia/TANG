package com.microsoft.tang.types;

import java.util.Set;

import com.microsoft.tang.exceptions.BindException;

public interface ClassNode<T> extends Node {
  public boolean getIsPrefixTarget();

  public ConstructorDef<T>[] getInjectableConstructors();

  public ConstructorDef<T> getConstructorDef(ClassNode<?>... args)
      throws BindException;

  public ConstructorDef<T>[] getAllConstructors();

  public void putImpl(ClassNode<? extends T> impl);
  public Set<ClassNode<? extends T>> getKnownImplementations();
  
  public boolean isInjectionCandidate();
  public boolean isExternalConstructor();
}