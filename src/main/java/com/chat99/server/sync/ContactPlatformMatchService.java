package com.chat99.server.sync;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ContactPlatformMatchService {

    private final UserRepository userRepository;
    private final PhoneUtils phoneUtils;
    private final ObjectMapper json;

    public ContactPlatformMatchService(UserRepository userRepository,
                                       PhoneUtils phoneUtils,
                                       ObjectMapper json) {
        this.userRepository = userRepository;
        this.phoneUtils = phoneUtils;
        this.json = json;
    }

    public Map<String, Match> match(Collection<ContactCandidate> candidates) {
        Map<String, List<String>> normalizedByKey = new HashMap<>();
        Set<String> allPhones = new HashSet<>();
        for (ContactCandidate candidate : candidates) {
            List<String> normalized = candidate.phones().stream()
                .map(this::normalizePhone)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            normalizedByKey.put(candidate.key(), normalized);
            allPhones.addAll(normalized);
        }

        Map<String, User> usersByPhone = new HashMap<>();
        if (!allPhones.isEmpty()) {
            for (User user : userRepository.findByPhoneIn(allPhones)) {
                usersByPhone.put(user.getPhone(), user);
            }
        }

        Map<String, Match> result = new HashMap<>();
        for (ContactCandidate candidate : candidates) {
            Match match = Match.none();
            for (String phone : normalizedByKey.getOrDefault(candidate.key(), List.of())) {
                User matched = usersByPhone.get(phone);
                if (matched != null && !matched.getUserId().equals(candidate.ownerUserId())) {
                    match = new Match(true, matched.getUserId());
                    break;
                }
            }
            result.put(candidate.key(), match);
        }
        return result;
    }

    public List<String> parsePhones(String phonesJson) {
        if (phonesJson == null || phonesJson.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(phonesJson,
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return phoneUtils.normalizeContactPhone(value, "CN");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record ContactCandidate(
        String key, String ownerUserId, List<String> phones) {

        public ContactCandidate {
            phones = phones == null ? List.of() : phones;
        }
    }

    public record Match(boolean platformUser, String matchedUserId) {
        static Match none() {
            return new Match(false, null);
        }
    }
}
