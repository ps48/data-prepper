public class TelemetryData {
    public enum Type {
        LOG("LOG"),
        METRIC("METRIC"),
        TRACE("TRACE");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private final Type type;
    private final Object data;

    public TelemetryData(Type type, Object data) {
        this.type = type;
        this.data = data;
    }

    public Type getType() {
        return type;
    }

    public Object getData() {
        return data;
    }
}
