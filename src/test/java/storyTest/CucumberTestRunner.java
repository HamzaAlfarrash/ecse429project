package storyTest;

import io.cucumber.core.cli.Main;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class CucumberTestRunner {
    public static void main(String[] args) throws Exception {
        final long seed = 12345L;                 // deterministic shuffle
        final Random rng = new Random(seed);
        final Path featureRoot = resolveFeatureRoot();

        if (featureRoot == null) {
            System.out.println("No feature files directory found. Checked: src/test/resources/features and features. Skipping Cucumber run.");
            return;
        }

        // Collect all .feature files under the discovered feature root
        List<String> features = Files.walk(featureRoot)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".feature"))
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList());

        if (features.isEmpty()) {
            System.out.println("No feature files found under " + featureRoot + ". Skipping Cucumber run.");
            return;
        }

        // Shuffle feature execution order
        Collections.shuffle(features, rng);
        System.out.println("Feature run order (seed=" + seed + "):");
        features.forEach(System.out::println);

        // Build cucumber CLI args and run
        List<String> cli = new ArrayList<>(Arrays.asList(
                "--glue", "storyTest",
                "--plugin", "pretty"
        ));
        cli.addAll(features);

        Main.main(cli.toArray(new String[0]));
    }

    private static Path resolveFeatureRoot() throws IOException {
        List<Path> candidates = Arrays.asList(
                Paths.get("src/test/resources/features"),
                Paths.get("features")
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
