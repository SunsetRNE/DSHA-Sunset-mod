import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.LegacyTarReader;
import com.deepseekharness.app.util.BundledRootfsAsset;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class ToolReadback {
  public static void main(String[] args) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    long start = System.nanoTime();
    long[] counts = {0, 0, 0};
    try (var raw = new BufferedInputStream(Files.newInputStream(Path.of(args[0])), 65536);
         var input = BundledRootfsAsset.decode(raw, args[1]);
         var tar = new DigestInputStream(input, digest)) {
      LegacyTarReader.readTrustedAssets(tar, (entry, data) -> {
        if (counts[0] == 0) counts[2] = System.nanoTime() - start;
        counts[0]++;
        if (entry.type.equals("FILE")) counts[1] += entry.size;
      }, new BackupControl(null));
    }
    System.out.printf(Locale.ROOT, "%d,%d,%d,%d,%s%n", counts[2], System.nanoTime() - start,
        counts[0], counts[1], HexFormat.of().formatHex(digest.digest()));
  }
}
