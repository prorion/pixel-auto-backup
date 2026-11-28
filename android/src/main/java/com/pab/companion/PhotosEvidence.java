package com.pab.companion;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PhotosEvidence {
    private static final Pattern FORMAT = Pattern.compile("[\\p{Cf}]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Zs}]+");
    private static final Pattern ADDRESS = Pattern.compile("[a-z0-9.!#$%&'*+/=?^`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_LABEL = Pattern.compile("^(?:file\\s*name|파일\\s*이름|파일명)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BACKED = Pattern.compile(
            "^(?:backed up(?=$|[\\s(:.)])|백업\\s*(?:됨|완료됨|되었습니다)(?=$|[\\s(:.)]))");
    private static final Pattern ORIGINAL = Pattern.compile(
            "^(?:original quality|원본\\s*화질)(?=$|\\s*[:().]|\\s+\\d)");
    private static final Pattern ORIGINAL_BACKUP = Pattern.compile(
            "^(?:원본\\s*화질로\\s*백업(?:됨|되었습니다))(?=$|[\\s(:.)])");
    private static final Pattern CONFLICT = Pattern.compile(
            "^(?:not backed up|back up now|backing up|uploading|storage saver|high quality|express|"
            + "백업되지\\s*않음|백업되지\\s*않았습니다|지금\\s*백업|업로드\\s*중|백업\\s*중|저장용량\\s*절약|고화질|빠른\\s*백업)(?=$|[\\s(:.)])");

    private PhotosEvidence() {}

    static String clean(CharSequence raw) {
        if (raw == null) return "";
        return FORMAT.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("")
                .replace('\u00a0',' ').replace('\u202f',' ').trim();
    }

    private static String normal(String label) {
        return SPACES.matcher(clean(label)).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static boolean accountLabel(String text) {
        return text.equals("backup account") || text.equals("account & storage")
                || text.equals("account and storage") || text.equals("백업 계정")
                || text.equals("계정 및 저장용량") || text.equals("계정 및 스토리지");
    }

    private static boolean explicitAccount(String text) {
        return text.startsWith("backing up to ") || text.startsWith("backup account:")
                || text.startsWith("backup account ") || text.startsWith("백업 계정:")
                || text.startsWith("백업 계정 ") || text.endsWith("계정에 백업 중");
    }

    static boolean hasAccount(Collection<String> labels, String expected) {
        if (expected.isEmpty() || otherAccount(labels, expected)) return false;
        boolean heading = false;
        boolean matched = false;
        for (String label : labels) {
            String text=normal(label);
            if (accountLabel(text)) heading=true;
            Matcher address=ADDRESS.matcher(text);
            if (explicitAccount(text) && address.find() && address.group().equalsIgnoreCase(expected)) return true;
            if (text.equalsIgnoreCase(expected)) matched=true;
        }
        return heading && matched;
    }

    static boolean otherAccount(Collection<String> labels, String expected) {
        if (expected.isEmpty()) return false;
        boolean heading=false;
        for (String label : labels) if (accountLabel(normal(label))) heading=true;
        for (String label : labels) {
            String text=normal(label);
            Matcher address=ADDRESS.matcher(text);
            if (explicitAccount(text) && address.find() && !address.group().equalsIgnoreCase(expected)) return true;
            if (heading && ADDRESS.matcher(text).matches() && !text.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    static boolean backupDisabled(Collection<String> labels) {
        for (String label : labels) {
            String text=normal(label);
            if (text.equals("backup is off") || text.equals("backup off") || text.equals("백업 꺼짐")
                    || text.equals("백업이 사용 중지됨")) return true;
        }
        return false;
    }

    static boolean details(Collection<String> labels) {
        for (String label : labels) {
            String text=normal(label);
            if (text.equals("details") || text.equals("file information") || text.equals("file info")
                    || text.equals("세부정보") || text.equals("세부 정보") || text.equals("파일 정보")
                    || text.equals("세부사항") || text.equals("세부 사항") || text.equals("상세정보") || text.equals("상세 정보"))
                return true;
        }
        return false;
    }

    static final class Item {
        String name="";
        boolean backedUp, original, conflicts;
        boolean verified() { return !name.isEmpty() && backedUp && original && !conflicts; }
    }

    static Item inspect(Collection<String> labels, java.util.function.Predicate<String> eligible) {
        Item result=new Item();
        boolean multiple=false;
        for (String label : labels) {
            if (eligible.test(label)) {
                if (!result.name.isEmpty() && !result.name.equals(label)) multiple=true;
                result.name=label;
                continue;
            }
            for (String line : label.split("[\\r\\n•·]")) {
                String candidate=FILE_LABEL.matcher(clean(line)).replaceFirst("");
                if (eligible.test(candidate)) {
                    if (!result.name.isEmpty() && !result.name.equals(candidate)) multiple=true;
                    result.name=candidate;
                    continue;
                }
                String text=normal(line).replace('：',':');
                collectStatus(result,text);
                for (String part:text.split("[,;]")) collectStatus(result,part.trim());
            }
        }
        if (multiple) result.name="";
        return result;
    }

    private static void collectStatus(Item result,String text) {
        if (CONFLICT.matcher(text).find()) result.conflicts=true;
        if (ORIGINAL_BACKUP.matcher(text).find()) {
            result.backedUp=true;
            result.original=true;
        }
        Matcher original=ORIGINAL.matcher(text);
        if (original.find()) {
            result.original=true;
            String suffix=text.substring(original.end()).trim().replaceFirst("^[(:]\\s*","");
            if (BACKED.matcher(suffix).find()) result.backedUp=true;
            if (CONFLICT.matcher(suffix).find()) result.conflicts=true;
        }
        Matcher backed=BACKED.matcher(text);
        if (backed.find()) {
            result.backedUp=true;
            String suffix=text.substring(backed.end()).trim().replaceFirst("^(?:[(:]\\s*|in\\s+)","");
            if (ORIGINAL.matcher(suffix).find()) result.original=true;
            if (CONFLICT.matcher(suffix).find()) result.conflicts=true;
        }
    }

    static String redactedEvidence(Collection<String> labels) {
        StringBuilder selected=new StringBuilder();
        for (String label : labels) {
            String text=normal(label);
            if (!(text.contains("backup") || text.contains("backed up") || text.contains("quality")
                    || text.contains("백업") || text.contains("화질") || details(java.util.Collections.singleton(label)))) continue;
            String safe=ADDRESS.matcher(label).replaceAll("[계정]");
            if (safe.length()>100) safe=safe.substring(0,100)+"…";
            if (selected.length()+safe.length()>440) break;
            if (selected.length()>0) selected.append("\n");
            selected.append(safe);
        }
        return selected.toString();
    }
}
