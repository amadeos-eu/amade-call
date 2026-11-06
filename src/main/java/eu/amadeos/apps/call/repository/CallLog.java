package eu.amadeos.apps.call.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.commons.config.AppDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The recent calls, newest first, in {@code ~/.config/amadeos/call/calls.tsv}: one line per call with time,
 * key, direction, kind, length in seconds and name. Kept on this computer only; at most {@link #KEEP} lines.
 */
public class CallLog {
    private static final Logger LOG = LoggerFactory.getLogger(CallLog.class);
    public static final String FILE = "calls.tsv";
    public static final int KEEP = 200;

    private final Path file;
    private final List<CallRecord> calls = new ArrayList<>();

    @Inject
    public CallLog(AppDirectories directories) {
        this(directories.configFile(FILE));
    }

    public CallLog(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    public synchronized List<CallRecord> all() {
        return List.copyOf(calls);
    }

    public synchronized List<CallRecord> with(PeerKey key) {
        return calls.stream().filter(c -> c.key().equals(key)).toList();
    }

    public synchronized void add(CallRecord call) {
        calls.addFirst(call);
        while (calls.size() > KEEP) calls.removeLast();
        save();
    }

    public synchronized void clear() {
        calls.clear();
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 6);
                if (f.length < 6 || !PeerKey.valid(f[1])) continue;
                try {
                    calls.add(new CallRecord(new PeerKey(f[1]), f[5], Instant.parse(f[0]),
                            Duration.ofSeconds(Long.parseLong(f[4])), CallRecord.Direction.valueOf(f[2]),
                            CallKind.valueOf(f[3])));
                } catch (RuntimeException e) {
                    LOG.debug("Skipping a broken line in {}", file);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read {}", file, e);
        }
    }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (CallRecord c : calls) {
            b.append(c.start()).append('\t').append(c.key().hex()).append('\t').append(c.direction()).append('\t')
                    .append(c.kind()).append('\t').append(c.length().toSeconds()).append('\t')
                    .append(c.name().replaceAll("[\\t\\r\\n]", " ")).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not write {}", file, e);
        }
    }
}
