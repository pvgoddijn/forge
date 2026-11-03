package forge.app.camera;

/** What started a capture. The id appears in the log. */
enum CaptureTrigger {
    AUTO("auto"),
    SHUTTER("shutter");

    private final String id;

    CaptureTrigger(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }
}
