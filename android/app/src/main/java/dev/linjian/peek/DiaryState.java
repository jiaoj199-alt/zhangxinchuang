package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/** “TA 的日记”本地数据层。内容只保存在 App SharedPreferences 中。 */
public final class DiaryState {
    public static final String KEY_BOOKS = "ta_diary_books_json";
    public static final String KEY_ENTRIES = "ta_diary_entries_json";
    public static final String KEY_ANNOTATIONS = "ta_diary_annotations_json";
    public static final String DEFAULT_COVER = "default_soft_notebook";

    private DiaryState() { }

    public static JSONArray books(Context ctx) { return readArray(ctx, KEY_BOOKS); }
    public static JSONArray entries(Context ctx) { return readArray(ctx, KEY_ENTRIES); }
    public static JSONArray annotations(Context ctx) { return readArray(ctx, KEY_ANNOTATIONS); }

    public static JSONObject bookById(Context ctx, String id) { return findById(books(ctx), id); }
    public static JSONObject entryById(Context ctx, String id) { return findById(entries(ctx), id); }

    public static JSONObject createBook(Context ctx, String name, String subtitle, String coverStyle) {
        JSONObject out = new JSONObject();
        try {
            name = clean(name);
            if (name.isEmpty()) name = "TA 的日记";
            JSONObject book = new JSONObject();
            book.put("id", "book_" + UUID.randomUUID().toString());
            book.put("name", limit(name, 60));
            book.put("subtitle", limit(clean(subtitle).isEmpty() ? "把今天看见的你，轻轻写下来。" : clean(subtitle), 100));
            book.put("cover_style", clean(coverStyle).isEmpty() ? DEFAULT_COVER : limit(clean(coverStyle), 80));
            book.put("cover_uri", "");
            book.put("created_at", now());
            book.put("updated_at", now());
            JSONArray all = books(ctx); all.put(book);
            if (!saveArray(ctx, KEY_BOOKS, all)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("book", book).put("book_id", book.optString("id")).put("message", "日记本已创建");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject renameBook(Context ctx, String id, String oldName, String name, String subtitle) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = books(ctx);
            JSONObject book = findById(all, clean(id));
            if (book == null) book = findBookByName(all, clean(oldName));
            if (book == null && all.length() == 1) book = all.optJSONObject(0);
            if (book == null) {
                JSONArray choices = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject b = all.optJSONObject(i);
                    if (b == null) continue;
                    choices.put(new JSONObject().put("book_id", b.optString("id")).put("name", b.optString("name")).put("subtitle", b.optString("subtitle")));
                }
                return out.put("ok", false).put("error", "book_not_found_or_ambiguous").put("message", "没有找到唯一日记本；请传 book_id 或 old_name。若只有一个日记本，本工具会自动重命名，不需要先 list_diary_books。").put("choices", choices);
            }
            String newName = clean(name);
            if (newName.isEmpty()) return out.put("ok", false).put("error", "name_required").put("message", "需要提供 new_name/name。");
            book.put("name", limit(newName, 60));
            if (subtitle != null) book.put("subtitle", limit(clean(subtitle), 100));
            book.put("updated_at", now());
            if (!saveArray(ctx, KEY_BOOKS, all)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("book", book).put("book_id", book.optString("id")).put("name", book.optString("name")).put("message", "日记本已重命名");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject updateCover(Context ctx, String id, String coverStyle, String coverUri) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = books(ctx); JSONObject book = findById(all, id);
            if (book == null) return out.put("ok", false).put("error", "book_not_found");
            if (coverStyle != null && !clean(coverStyle).isEmpty()) book.put("cover_style", limit(clean(coverStyle), 80));
            if (coverUri != null) book.put("cover_uri", limit(clean(coverUri), 1000));
            book.put("updated_at", now());
            if (!saveArray(ctx, KEY_BOOKS, all)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("book", book).put("book_id", id).put("message", "日记本封面已更新");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject writeEntry(Context ctx, String bookId, String title, String content, String mood, JSONArray tags, String date, String timeLabel) {
        return writeEntry(ctx, bookId, "", title, content, mood, tags, date, timeLabel);
    }

    public static JSONObject writeEntry(Context ctx, String bookId, String bookName, String title, String content, String mood, JSONArray tags, String date, String timeLabel) {
        JSONObject out = new JSONObject();
        try {
            String originalBookId = clean(bookId);
            String preferredBookName = clean(bookName);
            JSONArray allBooks = books(ctx);
            JSONObject book = findById(allBooks, originalBookId);
            if (book == null) book = findBookByName(allBooks, preferredBookName);
            if (book == null && allBooks.length() == 1) book = allBooks.optJSONObject(0);

            boolean autoCreatedBook = false;
            if (book == null && allBooks.length() == 0) {
                JSONObject created = createBook(ctx, preferredBookName.isEmpty() ? "TA 的日记" : preferredBookName, "把今天看见的你，轻轻写下来。", DEFAULT_COVER);
                if (!created.optBoolean("ok", false)) return out.put("ok", false).put("error", "auto_create_book_failed").put("message", "没有可用日记本，自动创建默认日记本失败。").put("detail", created);
                book = bookById(ctx, created.optString("book_id", ""));
                autoCreatedBook = true;
            }

            if (book == null) {
                JSONArray choices = new JSONArray();
                for (int i = 0; i < allBooks.length(); i++) {
                    JSONObject b = allBooks.optJSONObject(i);
                    if (b == null) continue;
                    choices.put(new JSONObject().put("book_id", b.optString("id")).put("name", b.optString("name")).put("subtitle", b.optString("subtitle")));
                }
                return out.put("ok", false)
                        .put("error", "book_not_found_or_ambiguous")
                        .put("message", "没有找到唯一日记本；请传最新 book_id 或 book_name。若只有一本或没有日记本，本工具会自动兜底写入。")
                        .put("old_book_id", originalBookId)
                        .put("book_name", preferredBookName)
                        .put("choices", choices);
            }

            String resolvedBookId = book.optString("id", "");
            if (clean(content).isEmpty()) return out.put("ok", false).put("error", "content_required");
            JSONObject entry = new JSONObject();
            entry.put("id", "entry_" + UUID.randomUUID().toString());
            entry.put("book_id", resolvedBookId);
            entry.put("title", limit(clean(title).isEmpty() ? "没有标题的一页" : clean(title), 100));
            entry.put("content", limit(clean(content), 12000));
            entry.put("mood", limit(clean(mood), 40));
            entry.put("tags", normalizeTags(tags));
            entry.put("date", normalizeDate(date));
            entry.put("time_label", limit(clean(timeLabel), 30));
            entry.put("created_at", now());
            entry.put("updated_at", now());
            JSONArray all = entries(ctx); all.put(entry);
            if (!saveArray(ctx, KEY_ENTRIES, all)) return out.put("ok", false).put("error", "save_failed");
            touchBook(ctx, resolvedBookId);
            return out.put("ok", true)
                    .put("entry", entry)
                    .put("entry_id", entry.optString("id"))
                    .put("book_id", resolvedBookId)
                    .put("book_name", book.optString("name"))
                    .put("old_book_id", originalBookId)
                    .put("auto_created_book", autoCreatedBook)
                    .put("title", entry.optString("title"))
                    .put("date", entry.optString("date"))
                    .put("message", autoCreatedBook ? "已自动创建默认日记本并写入日记" : "日记已写入本机");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONArray listEntries(Context ctx, String bookId) {
        return filterEntries(ctx, bookId, "", "", "", new JSONArray());
    }

    public static JSONArray search(Context ctx, String bookId, String keyword, String from, String to, JSONArray tags) {
        return filterEntries(ctx, bookId, keyword, from, to, tags);
    }

    public static JSONObject updateEntry(Context ctx, String id, JSONObject values) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = entries(ctx); JSONObject entry = findById(all, id);
            if (entry == null) return out.put("ok", false).put("error", "entry_not_found");
            if (values.has("title")) entry.put("title", limit(clean(values.optString("title", "")), 100));
            if (values.has("content")) {
                String content = clean(values.optString("content", ""));
                if (content.isEmpty()) return out.put("ok", false).put("error", "content_required");
                entry.put("content", limit(content, 12000));
            }
            if (values.has("mood")) entry.put("mood", limit(clean(values.optString("mood", "")), 40));
            if (values.has("tags")) entry.put("tags", normalizeTags(values.optJSONArray("tags")));
            if (values.has("date")) entry.put("date", normalizeDate(values.optString("date", "")));
            if (values.has("time_label")) entry.put("time_label", limit(clean(values.optString("time_label", "")), 30));
            entry.put("updated_at", now());
            if (!saveArray(ctx, KEY_ENTRIES, all)) return out.put("ok", false).put("error", "save_failed");
            touchBook(ctx, entry.optString("book_id", ""));
            return out.put("ok", true).put("entry", entry).put("entry_id", id).put("book_id", entry.optString("book_id")).put("title", entry.optString("title")).put("date", entry.optString("date")).put("message", "日记已更新");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject deleteEntry(Context ctx, String id) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = entries(ctx), next = new JSONArray(); JSONObject removed = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.optJSONObject(i); if (entry == null) continue;
                if (id.equals(entry.optString("id", ""))) removed = entry; else next.put(entry);
            }
            if (removed == null) return out.put("ok", false).put("error", "entry_not_found");
            JSONArray allNotes = annotations(ctx), nextNotes = new JSONArray(); int removedNotes = 0;
            for (int i = 0; i < allNotes.length(); i++) {
                JSONObject note = allNotes.optJSONObject(i); if (note == null) continue;
                if (id.equals(note.optString("entry_id", ""))) removedNotes++; else nextNotes.put(note);
            }
            if (!AppPrefs.get(ctx).edit().putString(KEY_ENTRIES, next.toString()).putString(KEY_ANNOTATIONS, nextNotes.toString()).commit()) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("entry_id", id).put("book_id", removed.optString("book_id")).put("title", removed.optString("title")).put("date", removed.optString("date")).put("deleted_annotation_count", removedNotes).put("message", "日记已删除");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject deleteBook(Context ctx, String id) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = books(ctx), nextBooks = new JSONArray(); JSONObject removed = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject book = all.optJSONObject(i); if (book == null) continue;
                if (id.equals(book.optString("id", ""))) removed = book; else nextBooks.put(book);
            }
            if (removed == null) return out.put("ok", false).put("error", "book_not_found");
            JSONArray allEntries = entries(ctx), nextEntries = new JSONArray(); int removedEntries = 0;
            for (int i = 0; i < allEntries.length(); i++) {
                JSONObject entry = allEntries.optJSONObject(i); if (entry == null) continue;
                if (id.equals(entry.optString("book_id", ""))) removedEntries++; else nextEntries.put(entry);
            }
            JSONArray allNotes = annotations(ctx), nextNotes = new JSONArray(); int removedNotes = 0;
            for (int i = 0; i < allNotes.length(); i++) {
                JSONObject note = allNotes.optJSONObject(i); if (note == null) continue;
                if (id.equals(note.optString("book_id", ""))) removedNotes++; else nextNotes.put(note);
            }
            if (!AppPrefs.get(ctx).edit().putString(KEY_BOOKS, nextBooks.toString()).putString(KEY_ENTRIES, nextEntries.toString()).putString(KEY_ANNOTATIONS, nextNotes.toString()).commit()) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("book_id", id).put("name", removed.optString("name")).put("deleted_entry_count", removedEntries).put("deleted_annotation_count", removedNotes).put("message", "日记本和其中的日记已删除");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONArray annotationsForEntry(Context ctx, String entryId) {
        return listAnnotations(ctx, "", entryId, false).optJSONArray("annotations");
    }

    public static JSONObject addAnnotation(Context ctx, String entryId, int paragraphIndex, String quoteText, String annotationText, String mood, String style, String createdBy) {
        JSONObject out = new JSONObject();
        try {
            JSONObject entry = entryById(ctx, clean(entryId));
            if (entry == null) return out.put("ok", false).put("error", "entry_not_found").put("message", "没有找到这篇日记，纸条没有保存。");
            String text = clean(annotationText);
            if (text.isEmpty()) return out.put("ok", false).put("error", "annotation_required").put("message", "纸条内容不能为空。");
            JSONObject note = new JSONObject();
            note.put("id", "anno_" + UUID.randomUUID().toString());
            note.put("entry_id", entry.optString("id", ""));
            note.put("book_id", entry.optString("book_id", ""));
            note.put("paragraph_index", Math.max(0, paragraphIndex));
            note.put("quote_text", limit(clean(quoteText), 800));
            note.put("annotation_text", limit(text, 1000));
            note.put("mood", limit(clean(mood).isEmpty() ? "想回应" : clean(mood), 30));
            note.put("style", limit(clean(style).isEmpty() ? "margin_note" : clean(style), 40));
            note.put("created_by", limit(clean(createdBy).isEmpty() ? "user" : clean(createdBy), 30));
            note.put("seen_by_companion", false);
            note.put("created_at", now());
            note.put("updated_at", now());
            JSONArray all = annotations(ctx); all.put(note);
            if (!saveArray(ctx, KEY_ANNOTATIONS, all)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("annotation", note).put("annotation_id", note.optString("id")).put("entry_id", entry.optString("id")).put("book_id", entry.optString("book_id")).put("message", "页边纸条已保存");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject listAnnotations(Context ctx, String bookId, String entryId, boolean unreadOnly) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = annotations(ctx), found = new JSONArray();
            String b = clean(bookId), e = clean(entryId);
            for (int i = 0; i < all.length(); i++) {
                JSONObject note = all.optJSONObject(i); if (note == null) continue;
                if (!b.isEmpty() && !b.equals(note.optString("book_id", ""))) continue;
                if (!e.isEmpty() && !e.equals(note.optString("entry_id", ""))) continue;
                if (unreadOnly && note.optBoolean("seen_by_companion", false)) continue;
                found.put(note);
            }
            return out.put("ok", true).put("book_id", b).put("entry_id", e).put("unread_only", unreadOnly).put("annotations", found).put("count", found.length()).put("message", "已读取页边纸条");
        } catch (Exception ex) { return error(out, ex); }
    }

    public static JSONObject readEntryWithAnnotations(Context ctx, String entryId, boolean markSeen) {
        JSONObject out = new JSONObject();
        try {
            JSONObject entry = entryById(ctx, clean(entryId));
            if (entry == null) return out.put("ok", false).put("error", "entry_not_found");
            JSONObject notes = listAnnotations(ctx, "", entry.optString("id", ""), false);
            if (markSeen) markAnnotationsSeen(ctx, "", entry.optString("id", ""));
            return out.put("ok", true).put("entry", entry).put("annotations", notes.optJSONArray("annotations")).put("annotation_count", notes.optInt("count", 0)).put("message", "已读取日记和页边纸条");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject markAnnotationsSeen(Context ctx, String annotationId, String entryId) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = annotations(ctx); int changed = 0; String id = clean(annotationId), e = clean(entryId);
            for (int i = 0; i < all.length(); i++) {
                JSONObject note = all.optJSONObject(i); if (note == null) continue;
                boolean hit = (!id.isEmpty() && id.equals(note.optString("id", ""))) || (!e.isEmpty() && e.equals(note.optString("entry_id", "")));
                if (hit && !note.optBoolean("seen_by_companion", false)) { note.put("seen_by_companion", true); note.put("updated_at", now()); changed++; }
            }
            if (!saveArray(ctx, KEY_ANNOTATIONS, all)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("changed_count", changed).put("message", "页边纸条已标记为已看");
        } catch (Exception ex) { return error(out, ex); }
    }

    public static JSONObject deleteAnnotation(Context ctx, String annotationId) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = annotations(ctx), next = new JSONArray(); JSONObject removed = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject note = all.optJSONObject(i); if (note == null) continue;
                if (clean(annotationId).equals(note.optString("id", ""))) removed = note; else next.put(note);
            }
            if (removed == null) return out.put("ok", false).put("error", "annotation_not_found");
            if (!saveArray(ctx, KEY_ANNOTATIONS, next)) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("annotation_id", annotationId).put("entry_id", removed.optString("entry_id")).put("message", "页边纸条已删除");
        } catch (Exception e) { return error(out, e); }
    }

    public static int annotationCount(Context ctx, String entryId) {
        JSONArray arr = annotationsForEntry(ctx, entryId);
        return arr == null ? 0 : arr.length();
    }

    public static JSONObject exportBundle(Context ctx) {
        JSONObject out = new JSONObject();
        try { return out.put("format", "linjian-ta-diary-backup").put("version", 2).put("exported_at", now()).put("books", books(ctx)).put("entries", entries(ctx)).put("annotations", annotations(ctx)); }
        catch (Exception e) { return error(out, e); }
    }

    public static JSONObject importBundle(Context ctx, String raw) {
        JSONObject out = new JSONObject();
        try {
            JSONObject bundle = new JSONObject(raw); JSONArray incomingBooks = bundle.optJSONArray("books"), incomingEntries = bundle.optJSONArray("entries"), incomingAnnotations = bundle.optJSONArray("annotations");
            if (incomingBooks == null || incomingEntries == null) return out.put("ok", false).put("error", "invalid_backup");
            JSONArray mergedBooks = mergeById(books(ctx), incomingBooks, "book_");
            JSONArray mergedEntries = mergeById(entries(ctx), incomingEntries, "entry_");
            JSONArray mergedAnnotations = mergeById(annotations(ctx), incomingAnnotations == null ? new JSONArray() : incomingAnnotations, "anno_");
            if (!AppPrefs.get(ctx).edit().putString(KEY_BOOKS, mergedBooks.toString()).putString(KEY_ENTRIES, mergedEntries.toString()).putString(KEY_ANNOTATIONS, mergedAnnotations.toString()).commit()) return out.put("ok", false).put("error", "save_failed");
            return out.put("ok", true).put("book_count", mergedBooks.length()).put("entry_count", mergedEntries.length()).put("annotation_count", mergedAnnotations.length()).put("message", "日记备份已导入");
        } catch (Exception e) { return error(out, e); }
    }

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "");
            if ("create_diary_book".equals(action)) out = createBook(ctx, cmd.optString("name"), cmd.optString("subtitle"), cmd.optString("cover_style", DEFAULT_COVER));
            else if ("list_diary_books".equals(action)) out.put("ok", true).put("books", books(ctx)).put("message", "已读取本机日记本列表");
            else if ("rename_diary_book".equals(action)) out = renameBook(ctx, cmd.optString("book_id"), cmd.optString("old_name"), firstNonEmpty(cmd.optString("new_name"), cmd.optString("name")), cmd.has("subtitle") ? cmd.optString("subtitle") : null);
            else if ("update_diary_book_cover".equals(action)) out = updateCover(ctx, cmd.optString("book_id"), cmd.has("cover_style") ? cmd.optString("cover_style") : null, cmd.has("cover_uri") ? cmd.optString("cover_uri") : null);
            else if ("write_diary_entry".equals(action)) out = writeEntry(ctx, cmd.optString("book_id"), firstNonEmpty(cmd.optString("book_name"), cmd.optString("book_title")), cmd.optString("title"), cmd.optString("content"), cmd.optString("mood"), cmd.optJSONArray("tags"), cmd.optString("date"), cmd.optString("time_label"));
            else if ("list_diary_entries".equals(action)) out.put("ok", true).put("book_id", cmd.optString("book_id")).put("entries", listEntries(ctx, cmd.optString("book_id"))).put("message", "已读取本机日记列表");
            else if ("read_diary_entry".equals(action)) { JSONObject entry = entryById(ctx, cmd.optString("entry_id")); if (entry == null) out.put("ok", false).put("error", "entry_not_found"); else out.put("ok", true).put("entry", entry).put("message", "已读取日记"); }
            else if ("read_diary_entry_with_annotations".equals(action)) out = readEntryWithAnnotations(ctx, cmd.optString("entry_id"), cmd.optBoolean("mark_seen", false));
            else if ("add_diary_annotation".equals(action)) out = addAnnotation(ctx, cmd.optString("entry_id"), cmd.optInt("paragraph_index", 0), cmd.optString("quote_text"), cmd.optString("annotation_text", cmd.optString("content")), cmd.optString("mood"), cmd.optString("style"), cmd.optString("created_by", "user"));
            else if ("list_diary_annotations".equals(action)) out = listAnnotations(ctx, cmd.optString("book_id"), cmd.optString("entry_id"), cmd.optBoolean("unread_only", false));
            else if ("mark_diary_annotations_seen".equals(action)) out = markAnnotationsSeen(ctx, cmd.optString("annotation_id"), cmd.optString("entry_id"));
            else if ("delete_diary_annotation".equals(action)) out = cmd.optBoolean("confirm", false) ? deleteAnnotation(ctx, cmd.optString("annotation_id")) : out.put("ok", false).put("error", "confirmation_required");
            else if ("search_diary_entries".equals(action)) out.put("ok", true).put("book_id", cmd.optString("book_id")).put("entries", search(ctx, cmd.optString("book_id"), cmd.optString("keyword"), cmd.optString("date_from"), cmd.optString("date_to"), cmd.optJSONArray("tags"))).put("message", "日记搜索完成");
            else if ("update_diary_entry".equals(action)) out = updateEntry(ctx, cmd.optString("entry_id"), cmd);
            else if ("delete_diary_entry".equals(action)) out = cmd.optBoolean("confirm", false) ? deleteEntry(ctx, cmd.optString("entry_id")) : out.put("ok", false).put("error", "confirmation_required");
            else if ("delete_diary_book".equals(action)) out = cmd.optBoolean("confirm", false) ? deleteBook(ctx, cmd.optString("book_id")) : out.put("ok", false).put("error", "confirmation_required");
            else out.put("ok", false).put("error", "unknown_diary_action");
            out.put("result", out.toString());
        } catch (Exception e) { out = error(out, e); try { out.put("result", out.toString()); } catch (Exception ignored) { } }
        return out;
    }

    private static JSONArray filterEntries(Context ctx, String bookId, String keyword, String from, String to, JSONArray requiredTags) {
        ArrayList<JSONObject> found = new ArrayList<>(); String needle = clean(keyword).toLowerCase(Locale.ROOT);
        HashSet<String> wanted = new HashSet<>();
        if (requiredTags != null) for (int i = 0; i < requiredTags.length(); i++) { String t = clean(requiredTags.optString(i)).toLowerCase(Locale.ROOT); if (!t.isEmpty()) wanted.add(t); }
        JSONArray all = entries(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i); if (e == null || (!clean(bookId).isEmpty() && !bookId.equals(e.optString("book_id", "")))) continue;
            String date = e.optString("date", ""); if (!clean(from).isEmpty() && date.compareTo(from) < 0) continue; if (!clean(to).isEmpty() && date.compareTo(to) > 0) continue;
            JSONArray tags = e.optJSONArray("tags"); StringBuilder tagText = new StringBuilder(); HashSet<String> actual = new HashSet<>();
            if (tags != null) for (int j = 0; j < tags.length(); j++) { String t = tags.optString(j); tagText.append(' ').append(t); actual.add(t.toLowerCase(Locale.ROOT)); }
            if (!actual.containsAll(wanted)) continue;
            String haystack = (e.optString("title") + " " + e.optString("content") + " " + e.optString("mood") + tagText).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !haystack.contains(needle)) continue;
            found.add(e);
        }
        Collections.sort(found, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return (b.optString("date") + b.optString("created_at")).compareTo(a.optString("date") + a.optString("created_at")); } });
        JSONArray out = new JSONArray(); for (JSONObject e : found) out.put(e); return out;
    }

    private static JSONArray normalizeTags(JSONArray tags) {
        JSONArray out = new JSONArray(); HashSet<String> seen = new HashSet<>();
        if (tags != null) for (int i = 0; i < Math.min(tags.length(), 20); i++) { String t = limit(clean(tags.optString(i)), 30); if (!t.isEmpty() && seen.add(t)) out.put(t); }
        return out;
    }

    private static void touchBook(Context ctx, String id) throws Exception { JSONArray all = books(ctx); JSONObject book = findById(all, id); if (book != null) { book.put("updated_at", now()); saveArray(ctx, KEY_BOOKS, all); } }
    private static JSONObject findById(JSONArray all, String id) { if (id == null) return null; for (int i = 0; i < all.length(); i++) { JSONObject item = all.optJSONObject(i); if (item != null && id.equals(item.optString("id", ""))) return item; } return null; }
    private static JSONObject findBookByName(JSONArray all, String name) {
        String needle = clean(name).toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return null;
        JSONObject fuzzy = null; int fuzzyCount = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject book = all.optJSONObject(i); if (book == null) continue;
            String n = clean(book.optString("name")).toLowerCase(Locale.ROOT);
            if (n.equals(needle)) return book;
            if (n.contains(needle) || needle.contains(n)) { fuzzy = book; fuzzyCount++; }
        }
        return fuzzyCount == 1 ? fuzzy : null;
    }
    private static String firstNonEmpty(String a, String b) { return !clean(a).isEmpty() ? clean(a) : clean(b); }
    private static JSONArray readArray(Context ctx, String key) { try { return new JSONArray(AppPrefs.get(ctx).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private static boolean saveArray(Context ctx, String key, JSONArray value) { return AppPrefs.get(ctx).edit().putString(key, value.toString()).commit(); }
    private static JSONArray mergeById(JSONArray base, JSONArray incoming, String prefix) throws Exception { JSONArray out = new JSONArray(); HashSet<String> ids = new HashSet<>(); for (int i = 0; i < base.length(); i++) { JSONObject v = base.optJSONObject(i); if (v != null) { String id = v.optString("id"); if (!id.isEmpty()) ids.add(id); out.put(v); } } for (int i = 0; i < incoming.length(); i++) { JSONObject v = incoming.optJSONObject(i); if (v == null) continue; String id = v.optString("id"); if (id.isEmpty()) { id = prefix + UUID.randomUUID(); v.put("id", id); } if (!ids.contains(id)) { ids.add(id); out.put(v); } } return out; }
    private static String normalizeDate(String value) { String v = clean(value); if (v.matches("\\d{4}-\\d{2}-\\d{2}")) return v; return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()); }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String limit(String s, int max) { s = s == null ? "" : s; return s.length() <= max ? s : s.substring(0, max); }
    private static JSONObject error(JSONObject out, Exception e) { try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { } return out; }
}
