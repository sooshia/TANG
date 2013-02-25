package com.microsoft.tang;

import java.util.Collection;
import java.util.Set;

import com.microsoft.tang.exceptions.BindException;

/**
 * ClassHierarchy objects store information about the interfaces
 * and implementations that are available in a particular runtime
 * environment.
 * 
 * When Tang is running inside the same environment as the injected
 * objects, ClassHierarchy is simply a read-only representation of
 * information that is made available via language reflection.
 * 
 * If Tang is set up to perform remote injection, then the ClassHierarchy
 * it runs against is backed by a flat file, or other summary of the
 * libraries that will be available during injection.
 *
 */
public interface ClassHierarchy {

  public Node register(String s) throws BindException;
  
  public Collection<String> getShortNames();

  public String resolveShortName(String shortName);

  /**
   * TODO: Fix up output of TypeHierarchy!
   * 
   * @return
   */
  public String toPrettyString();

}