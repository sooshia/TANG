package com.microsoft.tang.implementation.java;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;

import com.microsoft.tang.ExternalConstructor;
import com.microsoft.tang.JavaConfigurationBuilder;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.tang.exceptions.InjectionException;
import com.microsoft.tang.formats.ParameterParser;

public class TestParameterParser {
  @Test
  public void testParameterParser() throws BindException {
    ParameterParser p = new ParameterParser();
    p.addParser(FooParser.class);
    Foo f = p.parse(Foo.class, "woot");
    Assert.assertEquals(f.s, "woot");
  }
  @Test(expected=UnsupportedOperationException.class)
  public void testUnregisteredParameterParser() throws BindException {
    ParameterParser p = new ParameterParser();
    //p.addParser(FooParser.class);
    Foo f = p.parse(Foo.class, "woot");
    Assert.assertEquals(f.s, "woot");
  }
  @Test
  public void testReturnSubclass() throws BindException {
    ParameterParser p = new ParameterParser();
    p.addParser(BarParser.class);
    Bar f = (Bar)p.parse(Foo.class, "woot");
    Assert.assertEquals(f.s, "woot");
    
  }
  @Test
  public void testGoodMerge() throws BindException {
    ParameterParser old = new ParameterParser();
    old.addParser(BarParser.class);
    ParameterParser nw = new ParameterParser();
    nw.mergeIn(old);
    nw.parse(Foo.class, "woot");
  }
  @Test
  public void testGoodMerge2() throws BindException {
    ParameterParser old = new ParameterParser();
    old.addParser(BarParser.class);
    ParameterParser nw = new ParameterParser();
    nw.addParser(BarParser.class);
    nw.mergeIn(old);
    nw.parse(Foo.class, "woot");
  }
  @Test(expected=IllegalArgumentException.class)
  public void testBadMerge() throws BindException {
    ParameterParser old = new ParameterParser();
    old.addParser(BarParser.class);
    ParameterParser nw = new ParameterParser();
    nw.addParser(FooParser.class);
    nw.mergeIn(old);
    nw.parse(Foo.class, "woot");
  }
  @Test
  public void testEndToEnd() throws BindException, InjectionException {
    Tang tang = Tang.Factory.getTang();
    JavaConfigurationBuilder cb = tang.newConfigurationBuilder();
    cb.bindParser(BarParser.class);
    cb.bindNamedParameter(SomeNamedFoo.class, "hdfs://woot");
    ILikeBars ilb = tang.newInjector(cb.build()).getInstance(ILikeBars.class);
    Assert.assertNotNull(ilb);
  }
  private static class FooParser implements ExternalConstructor<Foo> {
    private final Foo foo;
    @Inject
    public FooParser(String s) {
      this.foo = new Foo(s);
    }
    @Override
    public Foo newInstance() {
      return foo;
    }
    
  }
  private static class BarParser implements ExternalConstructor<Foo> {
    private final Bar bar;
    @Inject
    public BarParser(String s) {
      this.bar = new Bar(s);
    }
    @Override
    public Bar newInstance() {
      return bar;
    }
  }
  private static class Foo {
    public final String s;
    public Foo(String s) { this.s = s; }
  }
  private static class Bar extends Foo{
    public Bar(String s) { super(s); }
  }
  @NamedParameter
  private static class SomeNamedFoo implements Name<Foo> {}
  private static class ILikeBars {
    @Inject ILikeBars(@Parameter(SomeNamedFoo.class) Foo bar) {
      Bar b = (Bar) bar;
      Assert.assertEquals(b.s, "hdfs://woot");
    }
  }
}