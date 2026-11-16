package org.slf4j;
/** Stub for tests that run without the launcher libraries. */
public interface Logger {
  default void info(String s) {} default void info(String s, Object o) {} default void info(String s, Object a, Object b) {} default void info(String s, Object... o) {}
  default void warn(String s) {} default void warn(String s, Object o) {} default void warn(String s, Object a, Object b) {} default void warn(String s, Object... o) {}
  default void error(String s) {} default void error(String s, Object o) {} default void error(String s, Object a, Object b) {} default void error(String s, Object... o) {}
  default void debug(String s) {} default void debug(String s, Object o) {} default void debug(String s, Object... o) {}
}
