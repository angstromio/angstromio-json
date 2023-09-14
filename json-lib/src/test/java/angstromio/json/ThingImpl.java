package angstromio.json;

import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;

record ThingImpl(String value) implements Thing, Serializable {

    public int hashCode() {
        // This is specified in java.lang.Annotation.
        return (127 * "value".hashCode()) ^ value.hashCode();
    }

    /**
     * Thing specific equals
     */
    public boolean equals(Object o) {
        if (!(o instanceof Thing other)) {
            return false;
        }

        return value.equals(other.value());
    }

    public String toString() {
        return "@" + Thing.class.getName() + "(value=" + value + ")";
    }

    public Class<? extends Annotation> annotationType() {
        return Thing.class;
    }

    @Serial
    private static final long serialVersionUID = 0;
}