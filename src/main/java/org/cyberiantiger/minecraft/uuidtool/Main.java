/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cyberiantiger.minecraft.uuidtool;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 *
 * @author antony
 */
public class Main {
    private static final URL PROFILE_URL;
    static {
        try {
            PROFILE_URL = new URL("https://api.mojang.com/profiles/minecraft");
        } catch (MalformedURLException ex) {
            throw new IllegalStateException();
        }
    }
    // Maximum queries per 10 minutes.
    private static final int DEFAULT_RATE = 600;
    private static final Pattern VALID_USERNAME = Pattern.compile("[a-zA-Z0-9_]{2,16}");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BATCH_SIZE = 100; // Mojang's code says don't do over 100 at once.
    private static final Gson gson = new Gson();
    private static final String READ_STDIN = "";

    private static long lastQuery = 0L;
    private static long queryDelay = queryDelay(DEFAULT_RATE);

    private static long queryDelay(int rate) {
        if (rate <= 0)
            return 0L;
        // Round up, not down.
        return 1L + (10L * 60L * 1000L) / rate;
    }

    private static void usage() {
        System.err.println("Usage: java -jar uuid-tool.jar [-R <rate>] [-o] [<files>]");
        System.err.println("     -R <rate> - Maximum number of queries per 10 minutes, default 600"); 
        System.err.println("     -o        - offline mode");
        System.err.println("     -h        - show help");
        System.err.println("     <files>   - list of files containing usernames one per line, if not specified read from stdin");
    }

    public static void main(String[] args) throws Exception {
        Set<String> paths = new HashSet<String>();
        boolean parseFlags = true;
        boolean offline = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (parseFlags && arg.length() > 0 && arg.charAt(0) == '-') {
                if ("--".equals(arg)) {
                    parseFlags = false;
                } else if ("-".equals(arg)) {
                    paths.add(READ_STDIN);
                } else if ("-o".equals(arg)) {
                    offline = true;
                } else if ("-R".equals(arg)) {
                    i++;
                    if (args.length == i)  {
                        System.err.println("missing argument for -R");
                        usage();
                        System.exit(1);
                    }
                    try {
                        queryDelay = queryDelay(Integer.parseInt(args[i]));
                    } catch (NumberFormatException e) {
                        System.err.println("rate must be an integer: " + args[i]);
                        usage();
                        System.exit(1);
                    }
                } else if ("-h".equals(arg)) {
                    usage();
                    System.exit(1);
                } else {
                    System.err.println("Unexpected flag: " + arg);
                    usage();
                    System.exit(1);
                }
                continue;
            }
            paths.add(arg);
        }

        if (paths.isEmpty()) {
            paths.add(READ_STDIN);
        }

        Set<String> usernames = new HashSet<String>();
        for (String s : paths) {
            BufferedReader in = null;
            try {
                if (s == READ_STDIN) { // Deliberate equality
                    in = new BufferedReader(new InputStreamReader(System.in, UTF8));
                } else {
                    in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(s)), UTF8));
                }
                String line;
                while ( (line = in.readLine() ) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        if (VALID_USERNAME.matcher(line).matches()) {
                            usernames.add(line);
                        } else {
                            System.err.format("Invalid username: %s", line);
                            System.err.println();
                        }
                    }
                }
            } finally {
                if (in != null) {
                    in.close();
                }
            }
        }
        Map<String,UUID> uuids;
        if (offline) {
            uuids = new HashMap<String, UUID>();
            for (String s : usernames) {
                uuids.put(s, getOfflineUUID(s));
            }
        } else {
            uuids = getOnlineUUIDs(usernames);
        }
        for (Map.Entry<String, UUID> e : uuids.entrySet()) {
            System.out.format("%s %s", e.getKey(), e.getValue().toString());
            System.out.println();
        }
    }

    private static boolean isOfflineUUID(String player, UUID uuid) {
        return getOfflineUUID(player).equals(uuid);
    }

    private static UUID getOfflineUUID(String player) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(UTF8));
    }

    private static Map<String,UUID> getOnlineUUIDs(Collection<String> tmp) throws IOException {
        long now = System.currentTimeMillis();
        if (lastQuery + queryDelay > now) {
            try {
                Thread.sleep(lastQuery + queryDelay - now);
            } catch (InterruptedException ex) {
            }
            lastQuery += queryDelay;
        } else {
            lastQuery = now;
        }
        List<String> players = new ArrayList<String>(tmp);
        List<String> batch = new ArrayList<String>();
        Map<String,UUID> result = new HashMap<String,UUID>();
        while (!players.isEmpty()) {
            for (int i = 0; !players.isEmpty() && i < BATCH_SIZE; i++) {
                batch.add(players.remove(players.size()-1));
            }
            HttpURLConnection connection = (HttpURLConnection) PROFILE_URL.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type","application/json; encoding=UTF-8");
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(gson.toJson(batch).getBytes(UTF8));
            out.close();
            Reader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            Profile[] profiles = gson.fromJson(in, Profile[].class);
            for (Profile profile : profiles) {
                result.put(profile.getName(), profile.getUUID());
            }
            batch.clear();
        }
        return result;
    }
}
