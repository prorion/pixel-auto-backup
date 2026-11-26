package com.pab.companion;

import java.util.Collection;
import java.util.Locale;

final class PhotosEvidence {
    private PhotosEvidence() {}

    static String clean(CharSequence raw) {
        return raw == null ? "" : raw.toString().replaceAll("[\\p{Cf}]", "").trim();
    }

    static boolean hasAccount(Collection<String> labels, String expected) {
        if (expected.isEmpty() || otherAccount(labels, expected)) return false;
        boolean title = false, found = false;
        for (String label : labels) {
            String text = label.toLowerCase(Locale.ROOT);
            if (text.equals("backing up to " + expected) || text.equals(expected + " 계정에 백업 중")) return true;
            if (text.equals("backup account") || text.equals("백업 계정")) title = true;
            if (text.equals(expected)) found = true;
            if (text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") && !text.equals(expected)) return false;
        }
        return title && found;
    }

    static boolean otherAccount(Collection<String> labels, String expected) {
        if (expected.isEmpty()) return false;
        boolean backupAccount = labels.contains("Backup account") || labels.contains("백업 계정");
        for (String label : labels) {
            String text = label.toLowerCase(Locale.ROOT);
            if (text.startsWith("backing up to ") && !text.equals("backing up to " + expected)) return true;
            if (text.endsWith(" 계정에 백업 중") && !text.equals(expected + " 계정에 백업 중")) return true;
            if (backupAccount && text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") && !text.equals(expected)) return true;
        }
        return false;
    }

    static final class Item {
        String name = "";
        boolean backedUp, original, conflicts;
        boolean verified() { return !name.isEmpty() && backedUp && original && !conflicts; }
    }

    static Item inspect(Collection<String> labels, java.util.function.Predicate<String> eligible) {
        Item result = new Item();
        int matching = 0;
        for (String label : labels) {
            if (eligible.test(label)) {
                result.name = label;
                matching++;
            }
            for (String part : label.split("[•·\\n]")) {
                String text = part.trim().toLowerCase(Locale.ROOT);
                if (text.equals("backed up") || text.startsWith("backed up (") || text.startsWith("backed up:")
                        || text.equals("백업됨") || text.startsWith("백업됨(") || text.startsWith("백업됨 (")
                        || text.startsWith("백업됨:")) result.backedUp = true;
                if (text.equals("original quality") || text.equals("원본 화질")) result.original = true;
                if (text.equals("storage saver") || text.equals("저장용량 절약") || text.equals("저장용량 절약 화질")
                        || text.equals("not backed up") || text.equals("백업되지 않음")
                        || text.equals("back up now") || text.equals("지금 백업")) result.conflicts = true;
            }
        }
        if (matching != 1) result.name = "";
        return result;
    }
}
