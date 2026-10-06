package com.local.dasherfilter;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The app's user-visible name is one constant (AppName.NAME) with one twin in res/values/strings.xml for what Android
 * shows from the manifest: a rename changes those two lines, and nothing else spells the name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class AppNameTest {
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    public void androidShowsTheSameNameAsTheApp() throws PackageManager.NameNotFoundException {
        Application app = RuntimeEnvironment.getApplication();
        PackageManager packages = app.getPackageManager();
        assertEquals(AppName.NAME, app.getApplicationInfo().loadLabel(packages).toString());
        assertEquals(AppName.NAME, packages.getServiceInfo(new ComponentName(app, OfferFilterService.class), 0)
                .loadLabel(packages).toString());
        assertEquals(AppName.NAME + " background offers", packages.getServiceInfo(
                new ComponentName(app, OfferNotificationService.class), 0).loadLabel(packages).toString());
    }

    @Test
    public void noOtherSourceSpellsTheName() throws IOException {
        List<String> found = new ArrayList<>();
        File[] sources = new File(root(), "app/src/main/java/com/local/dasherfilter").listFiles();
        for (File source : sources) {
            if (source.getName().equals("AppName.java")) continue;
            List<String> lines = Files.readAllLines(source.toPath(), StandardCharsets.UTF_8);
            for (int n = 0; n < lines.size(); n++) {
                String line = lines.get(n).trim();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    if (literal.group().contains(AppName.NAME)) found.add(source.getName() + ":" + (n + 1));
                }
            }
        }
        String manifest = new String(Files.readAllBytes(new File(root(), "app/src/main/AndroidManifest.xml").toPath()),
                StandardCharsets.UTF_8);
        if (manifest.contains("android:label=\"" + AppName.NAME)) found.add("AndroidManifest.xml");
        assertTrue("spelled out instead of AppName.NAME: " + found, found.isEmpty());
        String strings = new String(Files.readAllBytes(new File(root(), "app/src/main/res/values/strings.xml").toPath()),
                StandardCharsets.UTF_8);
        assertTrue("strings.xml's entity is the name's one twin",
                strings.contains("<!ENTITY app \"" + AppName.NAME + "\">"));
    }

    /** The repository's root: the tests run from app/ (Gradle) or from the root. */
    private static File root() {
        return new File("../app").isDirectory() ? new File("..") : new File(".");
    }
}
