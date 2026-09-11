package io.github.beagle4j.jdbc.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Helpers shared by the JDBC invocation handlers.
 *
 * <h2>Why dynamic proxies rather than bytecode instrumentation</h2>
 * <p>Beagle could attach a Java agent and rewrite the driver, and an agent is still the
 * right tool for the parts of the system that are not interface-based. JDBC is not one
 * of them: it is defined entirely in terms of {@code Connection}, {@code Statement} and
 * {@code ResultSet} interfaces, so a {@link Proxy} sees everything an agent would.
 *
 * <p>What the proxy buys is compatibility. HikariCP, Druid, Tomcat JDBC and every
 * vendor driver already hand out their own proxies and wrappers; slotting in one more
 * layer of the same kind is something they are all built to tolerate, whereas rewriting
 * their bytecode from underneath them is how subtle breakage starts. It also means no
 * {@code -javaagent} flag, no class-loader ordering constraints, and no dependency on
 * the JVM's instrumentation API -- the difference between "add a dependency" and "change
 * how the application is launched", which is most of the reason a tool does or does not
 * get adopted.
 *
 * <p>The cost is reflective dispatch on every JDBC call. Measured against a database
 * round trip it is noise, and JDBC calls are by definition accompanied by one.
 */
public final class Proxies {

    private Proxies() {
    }

    /**
     * Wraps {@code target} in a proxy implementing every interface it exposes.
     *
     * <p>Collecting the full interface set matters: drivers and pools routinely
     * downcast to their own extensions, and a proxy that implements only
     * {@code java.sql.Connection} triggers a {@code ClassCastException} somewhere deep
     * in a pool the moment it does.
     */
    public static Object wrap(Object target, InvocationHandler handler) {
        Class<?>[] interfaces = allInterfaces(target.getClass());
        if (interfaces.length == 0) {
            return target;
        }
        return Proxy.newProxyInstance(
                bestClassLoader(target.getClass()), interfaces, handler);
    }

    private static ClassLoader bestClassLoader(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader != null ? loader : Proxies.class.getClassLoader();
    }

    private static Class<?>[] allInterfaces(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            collect(c, found);
        }
        return found.toArray(new Class<?>[0]);
    }

    private static void collect(Class<?> type, Set<Class<?>> into) {
        for (Class<?> itf : type.getInterfaces()) {
            // Non-public interfaces cannot be implemented by a proxy defined in
            // another package; including one would fail at proxy construction.
            if (java.lang.reflect.Modifier.isPublic(itf.getModifiers()) && into.add(itf)) {
                collect(itf, into);
            }
        }
    }

    /**
     * Invokes the real method, unwrapping the reflective exception so the caller sees
     * the {@link java.sql.SQLException} the driver actually threw. Leaking an
     * {@code InvocationTargetException} here would break every {@code catch
     * (SQLException e)} in the application.
     */
    public static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /**
     * Handles {@code equals}, {@code hashCode} and {@code toString} for a proxy.
     * Returns {@code null} when the method is not one of them.
     *
     * <p>Identity, not delegation: two proxies over the same connection must not compare
     * equal, because pools use identity to decide whether a connection has been
     * returned.
     */
    public static Object handleObjectMethod(Object proxy, Method method, Object[] args,
                                            Object delegate, String label) {
        if (method.getDeclaringClass() != Object.class) {
            return null;
        }
        return switch (method.getName()) {
            case "equals" -> Boolean.valueOf(proxy == (args == null ? null : args[0]));
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> label + "[" + delegate + "]";
            default -> null;
        };
    }
}
