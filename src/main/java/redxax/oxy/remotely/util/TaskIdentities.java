package redxax.oxy.remotely.util;

public final class TaskIdentities {
    public static volatile Access access = Access.BROWSER;

    private TaskIdentities() {
    }

    public static void install(Access value) {
        access = value != null ? value : Access.BROWSER;
    }

    public interface Access {
        Object current();

        void interrupt();

        String name();

        Access BROWSER = new Access() {
            private final Object identity = new Object();

            @Override
            public Object current() {
                return identity;
            }

            @Override
            public void interrupt() {
            }

            @Override
            public String name() {
                return "browser";
            }
        };
    }

    public static String failureName(Throwable error) {
        if (error == null) {
            return "none";
        }
        if (error instanceof IllegalArgumentException) {
            return "IllegalArgumentException";
        }
        if (error instanceof IllegalStateException) {
            return "IllegalStateException";
        }
        if (error instanceof NullPointerException) {
            return "NullPointerException";
        }
        if (error instanceof UnsupportedOperationException) {
            return "UnsupportedOperationException";
        }
        if (error instanceof IndexOutOfBoundsException) {
            return "IndexOutOfBoundsException";
        }
        if (error instanceof ArithmeticException) {
            return "ArithmeticException";
        }
        if (error instanceof ClassCastException) {
            return "ClassCastException";
        }
        if (error instanceof NumberFormatException) {
            return "NumberFormatException";
        }
        if (error instanceof RuntimeException) {
            return "RuntimeException";
        }
        if (error instanceof Exception) {
            return "Exception";
        }
        if (error instanceof Error) {
            return "Error";
        }
        return "Throwable";
    }

    public static String typeName(Object value) {
        if (value instanceof Throwable error) {
            return failureName(error);
        }
        return value == null ? "" : "Value";
    }
}
