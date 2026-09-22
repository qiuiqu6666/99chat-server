package com.chat99.server.official;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImProperties;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfficialAccountService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,30}$");

    private final OfficialAccountRepository repository;
    private final UserRepository userRepository;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final OfficialAccountProperties props;
    private final ImProperties imProps;
    private final OfficialAccountWelcomeService welcomeService;

    public OfficialAccountService(OfficialAccountRepository repository, UserRepository userRepository,
                                  ImAdminClient imAdmin, ImUserIdService imUserIdService,
                                  OfficialAccountProperties props,
                                  ImProperties imProps, OfficialAccountWelcomeService welcomeService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.props = props;
        this.imProps = imProps;
        this.welcomeService = welcomeService;
    }

    public record OfficialAccountView(
        String slug,
        String officialAccountId,
        String name,
        String introduction,
        String faceUrl,
        String organization,
        String ownerAccount,
        int maxSubscriberNum,
        boolean enabled,
        int sortOrder,
        Integer subscriberNum,
        Long createTime) {}

    public record CreateCommand(
        String slug,
        String name,
        String introduction,
        String faceUrl,
        String organization,
        String ownerUserId,
        Integer maxSubscriberNum,
        Integer sortOrder) {}

    public record UpdateCommand(
        String name,
        String introduction,
        String faceUrl,
        String organization,
        Integer maxSubscriberNum,
        Boolean enabled,
        Integer sortOrder) {}

    public record LinkCommand(
        String officialAccountId,
        String slug,
        String name,
        String introduction,
        String faceUrl,
        String organization,
        String ownerUserId,
        Integer sortOrder) {}

    @Transactional
    public OfficialAccountView linkExisting(LinkCommand cmd) {
        String officialAccountId = normalizeOfficialAccountId(cmd.officialAccountId());
        if (repository.findByOfficialAccountId(officialAccountId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OFFICIAL_ACCOUNT_ID_EXISTS");
        }
        String slug = cmd.slug() != null && !cmd.slug().isBlank()
            ? normalizeSlug(cmd.slug())
            : slugFromOfficialAccountId(officialAccountId);
        if (repository.findBySlug(slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OFFICIAL_ACCOUNT_SLUG_EXISTS");
        }

        Map<String, Object> imInfo = fetchImInfoOrThrow(officialAccountId);
        String owner = resolveOwnerForLink(cmd.ownerUserId(), imInfo.get("Owner_Account"));
        String name = firstNonBlank(cmd.name(), stringField(imInfo, "Name"), "官方公众号");
        String introduction = firstNonBlank(cmd.introduction(), stringField(imInfo, "Introduction"));
        String faceUrl = firstNonBlank(cmd.faceUrl(), stringField(imInfo, "FaceUrl"));
        String organization = firstNonBlank(cmd.organization(), stringField(imInfo, "Organization"));
        int maxSub = props.defaultMaxSubscribers();
        Object maxFromIm = imInfo.get("MaxSubscriberNum");
        if (maxFromIm instanceof Number n && n.intValue() > 0) {
            maxSub = n.intValue();
        }

        OfficialAccount row = new OfficialAccount();
        row.setSlug(slug);
        row.setOfficialAccountId(officialAccountId);
        row.setName(name);
        row.setIntroduction(introduction);
        row.setFaceUrl(faceUrl);
        row.setOrganization(organization);
        row.setOwnerAccount(owner);
        row.setMaxSubscriberNum(maxSub);
        row.setSortOrder(cmd.sortOrder() == null ? 0 : cmd.sortOrder());
        row.setEnabled(true);
        repository.save(row);
        return toView(row, numberField(imInfo, "SubscriberNum"));
    }

    public void ensurePrimaryLinked() {
        String id = props.primaryOfficialAccountId();
        if (id == null || id.isBlank()) {
            return;
        }
        String officialAccountId = normalizeOfficialAccountId(id);
        if (repository.findByOfficialAccountId(officialAccountId).isPresent()) {
            return;
        }
        linkExisting(new LinkCommand(
            officialAccountId,
            props.primarySlug(),
            null,
            null,
            null,
            null,
            null,
            0));
    }

    @Transactional
    public OfficialAccountView create(CreateCommand cmd) {
        String slug = normalizeSlug(cmd.slug());
        if (repository.findBySlug(slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OFFICIAL_ACCOUNT_SLUG_EXISTS");
        }
        String owner = resolveOwner(cmd.ownerUserId());
        String officialAccountId = props.idPrefix() + slug;
        int maxSub = cmd.maxSubscriberNum() == null ? props.defaultMaxSubscribers() : cmd.maxSubscriberNum();
        String createdId = imAdmin.createOfficialAccount(
            officialAccountId, imUserIdService.toIm(owner), cmd.name(), cmd.introduction(), cmd.faceUrl(),
            cmd.organization(), maxSub);

        OfficialAccount row = new OfficialAccount();
        row.setSlug(slug);
        row.setOfficialAccountId(createdId);
        row.setName(cmd.name());
        row.setIntroduction(cmd.introduction());
        row.setFaceUrl(cmd.faceUrl());
        row.setOrganization(cmd.organization());
        row.setOwnerAccount(owner);
        row.setMaxSubscriberNum(maxSub);
        row.setSortOrder(cmd.sortOrder() == null ? 0 : cmd.sortOrder());
        row.setEnabled(true);
        repository.save(row);
        return toView(row, null);
    }

    @Transactional
    public OfficialAccountView update(String officialAccountId, UpdateCommand cmd) {
        OfficialAccount row = mustFindByOfficialId(officialAccountId);
        if (cmd.name() != null) {
            row.setName(cmd.name());
        }
        if (cmd.introduction() != null) {
            row.setIntroduction(cmd.introduction());
        }
        if (cmd.faceUrl() != null) {
            row.setFaceUrl(cmd.faceUrl());
        }
        if (cmd.organization() != null) {
            row.setOrganization(cmd.organization());
        }
        if (cmd.maxSubscriberNum() != null) {
            row.setMaxSubscriberNum(cmd.maxSubscriberNum());
        }
        if (cmd.enabled() != null) {
            row.setEnabled(cmd.enabled());
        }
        if (cmd.sortOrder() != null) {
            row.setSortOrder(cmd.sortOrder());
        }
        imAdmin.modifyOfficialAccount(
            row.getOfficialAccountId(),
            cmd.name(),
            cmd.introduction(),
            cmd.faceUrl(),
            cmd.organization(),
            cmd.maxSubscriberNum());
        repository.save(row);
        return toView(row, fetchSubscriberNum(row.getOfficialAccountId()));
    }

    @Transactional
    public void delete(String officialAccountId) {
        OfficialAccount row = mustFindByOfficialId(officialAccountId);
        imAdmin.destroyOfficialAccount(row.getOfficialAccountId());
        repository.delete(row);
    }

    public List<OfficialAccountView> listPublic() {
        return listRows(repository.findByEnabledTrueOrderBySortOrderAscIdAsc(), true);
    }

    public List<OfficialAccountView> listAll() {
        return listRows(repository.findAllByOrderBySortOrderAscIdAsc(), true);
    }

    public OfficialAccountView getPublic(String officialAccountId) {
        OfficialAccount row = mustFindByOfficialId(officialAccountId);
        if (!row.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OFFICIAL_ACCOUNT_NOT_FOUND");
        }
        return toView(row, fetchSubscriberNum(row.getOfficialAccountId()));
    }

    public void subscribe(String officialAccountId, String userId) {
        OfficialAccount row = mustFindEnabled(officialAccountId);
        mustFindUser(userId);
        welcomeService.onSubscribed(row.getOfficialAccountId(), userId, true);
    }

    public void unsubscribe(String officialAccountId, String userId) {
        OfficialAccount row = mustFindEnabled(officialAccountId);
        imAdmin.deleteSubscriber(row.getOfficialAccountId(), imUserIdService.toIm(userId));
    }

    public List<Map<String, Object>> listSubscribedFromIm(String userId, int limit, int offset) {
        mustFindUser(userId);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        return imAdmin.getSubscribedOfficialAccounts(imUserIdService.toIm(userId), safeLimit, safeOffset);
    }

    public Map<String, Object> broadcast(String officialAccountId, String text) {
        String imId = resolveBroadcastOfficialAccountId(officialAccountId);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (text.length() > 12_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MESSAGE_TOO_LONG");
        }
        return imAdmin.sendOfficialAccountBroadcast(imId, text);
    }

    private String resolveBroadcastOfficialAccountId(String officialAccountId) {
        return repository.findByOfficialAccountId(officialAccountId)
            .map(OfficialAccount::getOfficialAccountId)
            .orElseGet(() -> {
                String primary = props.primaryOfficialAccountId();
                if (primary != null && primary.equals(officialAccountId)) {
                    return officialAccountId;
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OFFICIAL_ACCOUNT_NOT_FOUND");
            });
    }

    private List<OfficialAccountView> listRows(List<OfficialAccount> rows, boolean enrich) {
        List<OfficialAccountView> out = new ArrayList<>();
        Map<String, Integer> subscriberNums = enrich ? fetchSubscriberNums(rows) : Map.of();
        for (OfficialAccount row : rows) {
            out.add(toView(row, subscriberNums.get(row.getOfficialAccountId())));
        }
        return out;
    }

    private Map<String, Integer> fetchSubscriberNums(List<OfficialAccount> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> ids = rows.stream().map(OfficialAccount::getOfficialAccountId).toList();
            List<Map<String, Object>> infos = imAdmin.getOfficialAccountInfo(ids);
            Map<String, Integer> map = new LinkedHashMap<>();
            for (Map<String, Object> info : infos) {
                Object id = info.get("Official_Account");
                Object num = info.get("SubscriberNum");
                if (id != null && num instanceof Number n) {
                    map.put(id.toString(), n.intValue());
                }
            }
            return map;
        } catch (ImRestException e) {
            return Map.of();
        }
    }

    private Integer fetchSubscriberNum(String officialAccountId) {
        Map<String, Integer> map = fetchSubscriberNums(
            repository.findByOfficialAccountId(officialAccountId).map(List::of).orElse(List.of()));
        return map.get(officialAccountId);
    }

    private OfficialAccountView toView(OfficialAccount row, Integer subscriberNum) {
        Long createTime = null;
        try {
            List<Map<String, Object>> infos = imAdmin.getOfficialAccountInfo(List.of(row.getOfficialAccountId()));
            if (!infos.isEmpty()) {
                Object ct = infos.get(0).get("CreateTime");
                if (ct instanceof Number n) {
                    createTime = n.longValue();
                }
            }
        } catch (ImRestException ignored) {
            // IM 未配置或公众号功能未开通时仍返回本地资料
        }
        return new OfficialAccountView(
            row.getSlug(),
            row.getOfficialAccountId(),
            row.getName(),
            row.getIntroduction(),
            row.getFaceUrl(),
            row.getOrganization(),
            row.getOwnerAccount(),
            row.getMaxSubscriberNum(),
            row.isEnabled(),
            row.getSortOrder(),
            subscriberNum,
            createTime);
    }

    private String normalizeSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SLUG");
        }
        return slug;
    }

    private String resolveOwner(String ownerUserId) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            mustFindUser(ownerUserId);
            return ownerUserId;
        }
        String fallback = props.defaultOwnerUserId();
        if (fallback == null || fallback.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OWNER_USER_ID_REQUIRED");
        }
        mustFindUser(fallback);
        return fallback;
    }

    private String resolveOwnerForLink(String ownerUserId, Object imOwnerAccount) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            mustFindUser(ownerUserId);
            return ownerUserId;
        }
        String fromIm = imOwnerAccount == null ? null : imOwnerAccount.toString().trim();
        if (fromIm != null && !fromIm.isEmpty()) {
            return fromIm;
        }
        String fallback = props.defaultOwnerUserId();
        if (fallback != null && !fallback.isBlank()) {
            mustFindUser(fallback);
            return fallback;
        }
        return imProps.restAdminAccount();
    }

    private Map<String, Object> fetchImInfoOrThrow(String officialAccountId) {
        List<Map<String, Object>> infos = imAdmin.getOfficialAccountInfo(List.of(officialAccountId));
        if (infos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OFFICIAL_ACCOUNT_NOT_IN_IM");
        }
        Map<String, Object> info = infos.get(0);
        Object err = info.get("ErrorCode");
        if (err instanceof Number n && n.intValue() != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OFFICIAL_ACCOUNT_NOT_IN_IM");
        }
        return info;
    }

    private String normalizeOfficialAccountId(String officialAccountId) {
        if (officialAccountId == null || !officialAccountId.startsWith("@TOA#_")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OFFICIAL_ACCOUNT_ID");
        }
        return officialAccountId.trim();
    }

    private String slugFromOfficialAccountId(String officialAccountId) {
        int last = officialAccountId.lastIndexOf('_');
        String tail = last >= 0 ? officialAccountId.substring(last + 1) : officialAccountId;
        String slug = tail.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (!SLUG.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SLUG");
        }
        return slug;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer numberField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private void mustFindUser(String userId) {
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "OWNER_USER_NOT_FOUND"));
    }

    private OfficialAccount mustFindByOfficialId(String officialAccountId) {
        return repository.findByOfficialAccountId(officialAccountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OFFICIAL_ACCOUNT_NOT_FOUND"));
    }

    private OfficialAccount mustFindEnabled(String officialAccountId) {
        OfficialAccount row = mustFindByOfficialId(officialAccountId);
        if (!row.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OFFICIAL_ACCOUNT_NOT_FOUND");
        }
        return row;
    }
}
