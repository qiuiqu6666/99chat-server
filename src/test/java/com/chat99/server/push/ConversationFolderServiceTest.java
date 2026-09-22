package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.ConversationArchiveRealtimePublisher;
import com.chat99.server.realtime.ConversationFolderRealtimePublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ConversationFolderServiceTest {

    private static final String USER = "user001";
    private static final String PEER = "user002";
    private static final String GROUP = "@TGS#2ABCDEF";

    @Mock UserConversationFolderRepository folderRepository;
    @Mock UserConversationFolderMemberRepository memberRepository;
    @Mock UserConversationArchiveRepository archiveRepository;
    @Mock ConversationFolderRealtimePublisher folderRealtime;
    @Mock ConversationArchiveRealtimePublisher archiveRealtime;

    ConversationFolderService service;

    @BeforeEach
    void setup() {
        service = new ConversationFolderService(
            folderRepository, memberRepository, archiveRepository, folderRealtime, archiveRealtime);
    }

    @Test
    void createFolder_defaultsScopeAll() {
        when(folderRepository.countByUserId(USER)).thenReturn(0L);
        when(folderRepository.findMaxSortOrderByUserId(USER)).thenReturn(Optional.of(-1));
        when(folderRepository.existsByUserIdAndNameKeyAndFolderIdNot(eq(USER), eq("业务"), any()))
            .thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.FolderView view = service.upsert(
            USER, new ConversationFolderService.UpsertFolderRequest(null, "业务", null, null));

        assertThat(view.name()).isEqualTo("业务");
        assertThat(view.scope()).isEqualTo("all");
        assertThat(view.folderId()).isNotBlank();
        ArgumentCaptor<UserConversationFolder> captor = ArgumentCaptor.forClass(UserConversationFolder.class);
        verify(folderRepository).save(captor.capture());
        assertThat(captor.getValue().getNameKey()).isEqualTo("业务");
        verify(folderRealtime).singleChanged(eq(USER), eq(view.folderId()), eq("upsert"), anyLong());
    }

    @Test
    void createFolder_duplicateName_conflicts() {
        when(folderRepository.existsByUserIdAndNameKeyAndFolderIdNot(eq(USER), eq("业务"), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> service.upsert(
            USER, new ConversationFolderService.UpsertFolderRequest(null, " 业务 ", null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(rse.getReason()).isEqualTo("FOLDER_NAME_CONFLICT");
            });
        verify(folderRepository, never()).save(any());
    }

    @Test
    void renameFolder_sameNameDifferentCase_allowed() {
        UserConversationFolder existing = new UserConversationFolder();
        existing.setUserId(USER);
        existing.setFolderId("f1");
        existing.setName("Business");
        existing.setNameKey("business");
        existing.setScope("all");
        existing.setSortOrder(0);
        existing.setCreatedAt(1L);
        when(folderRepository.findById(new UserConversationFolderId(USER, "f1")))
            .thenReturn(Optional.of(existing));
        when(folderRepository.existsByUserIdAndNameKeyAndFolderIdNot(USER, "business", "f1"))
            .thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.findByUserIdAndFolderId(USER, "f1")).thenReturn(List.of());

        ConversationFolderService.FolderView view = service.upsert(
            USER, new ConversationFolderService.UpsertFolderRequest("f1", "BUSINESS", null, null));

        assertThat(view.name()).isEqualTo("BUSINESS");
        verify(folderRepository).save(any());
    }

    @Test
    void addMember_clearsArchive_andAllowsMixedChatTypes() {
        UserConversationFolder folder = new UserConversationFolder();
        folder.setUserId(USER);
        folder.setFolderId("f1");
        folder.setScope("all");
        folder.setName("业务");
        when(folderRepository.findById(new UserConversationFolderId(USER, "f1")))
            .thenReturn(Optional.of(folder));
        when(memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            USER, "c2c", PEER, "f1")).thenReturn(0);
        when(memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            USER, "group", GROUP, "f1")).thenReturn(0);
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(archiveRepository.existsById(new UserConversationArchiveId(USER, "c2c", PEER))).thenReturn(true);
        when(archiveRepository.existsById(new UserConversationArchiveId(USER, "group", GROUP))).thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.MembersMutationResponse resp = service.setMembers(
            USER, "f1", List.of(
                new ConversationFolderService.MemberMutationItem("c2c", PEER, true),
                new ConversationFolderService.MemberMutationItem("group", GROUP, true)));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.count()).isEqualTo(2);
        verify(archiveRepository).deleteById(new UserConversationArchiveId(USER, "c2c", PEER));
        verify(archiveRealtime).singleChanged(eq(USER), eq("c2c"), eq(PEER), eq(false), eq(null), anyLong());
        verify(folderRealtime).singleChanged(eq(USER), eq("f1"), eq("members"), anyLong());
    }

    @Test
    void addMember_removesFromOtherFolders_andBatchNotifies() {
        UserConversationFolder folder = new UserConversationFolder();
        folder.setUserId(USER);
        folder.setFolderId("f2");
        folder.setScope("all");
        when(folderRepository.findById(new UserConversationFolderId(USER, "f2")))
            .thenReturn(Optional.of(folder));
        when(memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            USER, "c2c", PEER, "f2")).thenReturn(1);
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(archiveRepository.existsById(any())).thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.MembersMutationResponse resp = service.setMembers(
            USER, "f2", List.of(new ConversationFolderService.MemberMutationItem("c2c", PEER, true)));

        assertThat(resp.ok()).isTrue();
        verify(memberRepository).deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            USER, "c2c", PEER, "f2");
        verify(folderRealtime).batchChanged(eq(USER), anyLong());
        verify(folderRealtime, never()).singleChanged(any(), any(), any(), anyLong());
    }

    @Test
    void legacyScopeFolder_stillAcceptsMixedMembers() {
        UserConversationFolder folder = new UserConversationFolder();
        folder.setUserId(USER);
        folder.setFolderId("f1");
        folder.setScope("c2c");
        when(folderRepository.findById(new UserConversationFolderId(USER, "f1")))
            .thenReturn(Optional.of(folder));
        when(memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(any(), any(), any(), any()))
            .thenReturn(0);
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(archiveRepository.existsById(any())).thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.MembersMutationResponse resp = service.setMembers(
            USER, "f1", List.of(new ConversationFolderService.MemberMutationItem("group", GROUP, true)));

        assertThat(resp.ok()).isTrue();
        verify(memberRepository).saveAndFlush(any());
    }

    @Test
    void replaceAll_acceptsScopeAllWithMixedMembers_andDedupesPeers() {
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.ReplaceResponse resp = service.replaceAll(USER, List.of(
            new ConversationFolderService.ReplaceFolderItem(
                "f1", "业务", "all", 0,
                List.of(new ConversationFolderService.ReplaceMemberItem("c2c", PEER))),
            new ConversationFolderService.ReplaceFolderItem(
                "f2", "其他", "all", 1,
                List.of(
                    new ConversationFolderService.ReplaceMemberItem("c2c", PEER),
                    new ConversationFolderService.ReplaceMemberItem("group", GROUP)))));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.folderCount()).isEqualTo(2);
        verify(memberRepository).deleteByUserId(USER);
        verify(folderRepository).deleteByUserId(USER);
        ArgumentCaptor<UserConversationFolderMember> memberCaptor =
            ArgumentCaptor.forClass(UserConversationFolderMember.class);
        verify(memberRepository, org.mockito.Mockito.times(2)).save(memberCaptor.capture());
        assertThat(memberCaptor.getAllValues())
            .extracting(UserConversationFolderMember::getFolderId)
            .containsExactlyInAnyOrder("f2", "f2");
        verify(folderRealtime).batchChanged(eq(USER), anyLong());
    }

    @Test
    void replaceAll_duplicateNames_conflict() {
        assertThatThrownBy(() -> service.replaceAll(USER, List.of(
            new ConversationFolderService.ReplaceFolderItem("f1", "业务", "all", 0, List.of()),
            new ConversationFolderService.ReplaceFolderItem("f2", "业务", "all", 1, List.of()))))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("FOLDER_NAME_CONFLICT");
    }

    @Test
    void moveMember_joinsTargetAndClearsArchive() {
        UserConversationFolder folder = new UserConversationFolder();
        folder.setUserId(USER);
        folder.setFolderId("f2");
        folder.setScope("all");
        when(folderRepository.findById(new UserConversationFolderId(USER, "f2")))
            .thenReturn(Optional.of(folder));
        when(memberRepository.deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
            USER, "c2c", PEER, "f2")).thenReturn(1);
        when(memberRepository.findById(any())).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(archiveRepository.existsById(new UserConversationArchiveId(USER, "c2c", PEER))).thenReturn(true);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationFolderService.MoveMemberResponse resp = service.moveMember(
            USER, new ConversationFolderService.MoveMemberRequest("c2c", PEER, "f2"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.folderId()).isEqualTo("f2");
        verify(archiveRepository).deleteById(new UserConversationArchiveId(USER, "c2c", PEER));
        verify(folderRealtime).batchChanged(eq(USER), anyLong());
    }

    @Test
    void folderLimit_isPerUserNotPerScope() {
        when(folderRepository.existsByUserIdAndNameKeyAndFolderIdNot(any(), any(), any()))
            .thenReturn(false);
        when(folderRepository.countByUserId(USER)).thenReturn(20L);

        assertThatThrownBy(() -> service.upsert(
            USER, new ConversationFolderService.UpsertFolderRequest(null, "overflow", "all", null)))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("FOLDER_LIMIT_EXCEEDED");
        verify(folderRepository, never()).save(any());
    }
}
