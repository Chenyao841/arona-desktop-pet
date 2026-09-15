package com.cy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class NeteaseService {

    private static final String BASE = "http://localhost:3000";
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Map<String, Object>> search(String keyword) {
        try {
            String json = rest.getForObject(BASE + "/search?keywords={kw}", String.class, keyword);
            JsonNode root = mapper.readTree(json);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode s : root) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.get("id").asText());
                m.put("name", s.get("name").asText());
                m.put("artist", s.get("artists").asText());
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public String getPlayUrl(String songId, String level) {
        try {
            String lv = (level == null || level.isEmpty()) ? "higher" : level;
            String json = rest.getForObject(BASE + "/url?id={id}&level={level}", String.class, songId, lv);
            JsonNode root = mapper.readTree(json);
            return root.has("url") && !root.get("url").isNull() ? root.get("url").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> getSimilar(String songId) {
        try {
            String json = rest.getForObject(BASE + "/simi?id={id}", String.class, songId);
            JsonNode root = mapper.readTree(json);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode s : root) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.get("id").asText());
                m.put("name", s.get("name").asText());
                m.put("artist", s.get("artists").asText());
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getHotSongs() {
        try {
            // 获取热歌榜ID，然后拉歌曲
            String json = rest.getForObject(BASE + "/playlist", String.class);
            JsonNode root = mapper.readTree(json);
            if (root.size() == 0) return Collections.emptyList();
            String playlistId = root.get(0).asText();
            String songsJson = rest.getForObject(BASE + "/playlist/songs?id={id}", String.class, playlistId);
            JsonNode songs = mapper.readTree(songsJson);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode s : songs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.get("id").asText());
                m.put("name", s.get("name").asText());
                m.put("artist", s.get("artists").asText());
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
