package fullforum.controllers;

import fullforum.data.models.Access;
import fullforum.data.models.Comment;
import fullforum.data.models.Document;
import fullforum.data.models.ViewRecord;
import fullforum.data.repos.*;
import fullforum.dto.in.CreateDocumentModel;
import fullforum.dto.in.PatchDocumentModel;
import fullforum.dto.out.IdDto;
import fullforum.dto.out.QDocument;
import fullforum.dto.out.UserPermission;
import fullforum.errhand.ForbidException;
import fullforum.errhand.NotFoundException;
import fullforum.errhand.UnauthorizedException;
import fullforum.services.IAuth;
import fullforum.services.Snowflake;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;

@Transactional
@RestController
@RequestMapping("/api/documents")
@Validated// PathVariable and params auto validation
public class DocumentController {
    @Autowired
    Snowflake snowflake;

    @Autowired
    IAuth auth;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    CommentRepository commentRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ReplyRepository replyRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Autowired
    ViewRecordRepository viewRecordRepository;

    @Autowired
    ELockRepository eLockRepository;


    @PostMapping
    public IdDto createDocument(@RequestBody @Valid CreateDocumentModel model) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        var document = new Document(snowflake.nextId(), auth.userId(), model.title, model.description, model.data);
        documentRepository.save(document);
        return new IdDto(document.getId());
    }

    @PatchMapping("{id}")
    public void patchDocument(@RequestBody @Valid PatchDocumentModel model, @PathVariable long id) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        var document = documentRepository.findById(id).orElse(null);
        if (document == null) {
            throw new NotFoundException("文档不存在");
        }
        //检查权限
        var userPermission = getCurrentUserPermission(id);

        if (model.data != null || model.title != null || model.description != null) {
            // 想要修改这几项
            if (userPermission.documentAccess != Access.ReadWrite) {
                throw new ForbidException("你没有权限");
            }
            document.setData(model.data == null ? document.getData() : model.data);
            document.setTitle(model.title == null ? document.getTitle() : model.title);
            document.setDescription(model.description == null ? document.getDescription() : model.description);
        }

        if (document.getTeamId() != null) { //如果为团队文档则队长可以修改文档的团队权限
            var team = teamRepository.findById(document.getTeamId()).orElse(null);
            assert team != null;//删除team的时候会清空doc的teamId

            if (auth.userId() == team.getLeaderId()) { //队长可以修改文档的团队权限
                if (model.teamId != null && model.teamId == -1) {
                    document.setTeamId(null);
                } else if (model.teamId != null) {
                    var teamInDb = teamRepository.findById(model.teamId).orElse(null);
                    if (teamInDb == null) {
                        throw new NotFoundException("该团队不存在");
                    }
                    document.setTeamId(model.teamId);
                }

                document.setTeamDocumentAccess(model.teamDocumentAccess == null ? document.getTeamCommentAccess()
                        : model.teamDocumentAccess);
                document.setTeamCommentAccess(model.teamCommentAccess == null ? document.getTeamCommentAccess()
                        : model.teamCommentAccess);
                document.setTeamCanShare(model.teamCanShare == null ? document.getTeamCanShare()
                        : model.teamCanShare);
            }
        }

        if (auth.userId() == document.getCreatorId()) { //创建者可以修改文档的所有权限
            if (model.teamId != null && model.teamId == -1) {
                document.setTeamId(null);
            } else if (model.teamId != null) {
                var teamInDb = teamRepository.findById(model.teamId).orElse(null);
                if (teamInDb == null) {
                    throw new NotFoundException("该团队不存在");
                }

                var membership = membershipRepository.findByUserIdAndTeamId(auth.userId(), teamInDb.getId());
                if (membership == null) {
                    throw new ForbidException("操作失败，你不在该团队中");
                }
                document.setTeamId(model.teamId);
            }
            document.setTeamDocumentAccess(model.teamDocumentAccess == null ? document.getTeamCommentAccess()
                    : model.teamDocumentAccess);
            document.setTeamCommentAccess(model.teamCommentAccess == null ? document.getTeamCommentAccess()
                    : model.teamCommentAccess);
            document.setTeamCanShare(model.teamCanShare == null ? document.getTeamCanShare()
                    : model.teamCanShare);
            document.setIsAbandoned(model.isAbandoned == null ? document.getIsAbandoned() : model.isAbandoned);
            document.setPublicDocumentAccess(model.publicDocumentAccess == null ? document.getPublicCommentAccess()
                    : model.publicDocumentAccess);
            document.setPublicCommentAccess(model.publicCommentAccess == null ? document.getPublicCommentAccess()
                    : model.publicCommentAccess);
            document.setPublicCanShare(model.publicCanShare == null ? document.getPublicCanShare()
                    : model.publicCanShare);
        }

        document.updatedAtNow();
        document.setModifyCountAndModifier(auth.userId());
        documentRepository.save(document);

        // 尝试释放锁 TODO: test
        var lock = eLockRepository.findELockByDocumentId(id);
        if (lock != null) {
            var uid = auth.userId();
            lock.tryRelease(uid);
            eLockRepository.save(lock);
        }
    }

    @DeleteMapping("{id}")
    public void removeDocument(@PathVariable Long id) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        var document = documentRepository.findById(id).orElse(null);
        if (document == null) {
            throw new NotFoundException("文档不存在");
        }
        if (document.getCreatorId() != auth.userId()) {
            throw new ForbidException("操作失败，你没有权限");
        }

        var comments = commentRepository.findAllByDocumentId(id);
        for (Comment comment : comments) {
            commentRepository.deleteById(comment.getId());
            replyRepository.deleteAllByCommentId(comment.getId());
        }

        documentRepository.deleteById(id);
    }

    @GetMapping("{id}")
    public QDocument getDocumentById(@PathVariable Long id) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        var document = documentRepository.findById(id).orElse(null);

        if (document == null) {
            return null;
        }

        var viewRecord = viewRecordRepository.findByDocumentIdAndUserId(document.getId(), auth.userId());
        if (viewRecord == null) {
            viewRecord = new ViewRecord(snowflake.nextId(), auth.userId(), document.getId());
        } else {
            viewRecord.updatedAtNow();
        }

        if (document.getCreatorId() == auth.userId()) {
            viewRecordRepository.save(viewRecord);
            return QDocument.convert(document, modelMapper);
        }

        var userPermission = getCurrentUserPermission(document.getId());
        if (userPermission.documentAccess != Access.None) {
            viewRecordRepository.save(viewRecord);
            return QDocument.convert(document, modelMapper);
        } else {
            throw new ForbidException("操作失败，你没有权限");
        }
    }


    @GetMapping
    public List<QDocument> getDocuments(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Boolean myfavorite,
            @RequestParam(required = false) Boolean isAbandoned,
            @RequestParam(required = false) Boolean recent
    ) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        List results;
        List<QDocument> documents = new ArrayList<>();

        if (myfavorite != null && myfavorite) { //返回当前用户收藏的文档
            var query = entityManager.createQuery(
                    "select d from Document d join Favorite f" +
                            " on d.id = f.documentId" +
                            " where f.userId = :userId" +
                            " and d.isAbandoned = false" +
                            " order by d.updatedAt desc ")
                    .setParameter("userId", auth.userId());
            results = query.getResultList();
        } else if (isAbandoned != null && isAbandoned) { //返回当前用户回收站内文档
            var query = entityManager.createQuery(
                    "select d from Document d" +
                            " where d.creatorId = :userId " +
                            " and d.isAbandoned = true" +
                            " order by d.updatedAt desc ")
                    .setParameter("userId", auth.userId());
            results = query.getResultList();
        } else if (recent != null && recent) { //返回当前用户最近浏览的文档
            var query = entityManager.createQuery(
                    "select d from Document d join ViewRecord v" +
                            " on d.id = v.documentId" +
                            " where (v.userId = :userId)" +
                            " and d.isAbandoned = false" +
                            " order by v.updatedAt desc ")
                    .setParameter("userId", auth.userId());
            results = query.getResultList();
            for (var result : results) {
                var document = (Document) result;
                documents.add(QDocument.convert(document, modelMapper));
                if (documents.size() >= 15) {
                    break;
                }
            }
            return documents;
        } else { //根据
            if (teamId != null) {
                var membership = membershipRepository.findByUserIdAndTeamId(auth.userId(), teamId);
                if (membership == null) {
                    throw new ForbidException("操作失败，你不在团队中");
                }
            }
            var query = entityManager.createQuery(
                    "select d from Document d" +
                            " where (:creatorId is null or d.creatorId = :creatorId)" +
                            " and (:teamId is null or d.teamId = :teamId)" +
                            " and d.isAbandoned = false" +
                            " order by d.updatedAt desc ")
                    .setParameter("creatorId", creatorId)
                    .setParameter("teamId", teamId);
            results = query.getResultList();
        }

        for (var result : results) {
            var document = (Document) result;
            if (auth.userId() != document.getCreatorId()) {//非文档创建者
                var permission = getCurrentUserPermission(document.getId());
                if (permission.documentAccess != Access.None) {
                    documents.add(QDocument.convert(document, modelMapper));
                }
            } else {
                documents.add(QDocument.convert(document, modelMapper));
            }
        }
        return documents;
    }

    private AccessorLevel getAccessorLevel(Document document, long accessorId) {
        if (accessorId == document.getCreatorId()) {
            return AccessorLevel.self;
        }

        if (document.getTeamId() == null) {
            return AccessorLevel.publicLevel;
        }

        var membership = membershipRepository.findByUserIdAndTeamId(accessorId, document.getTeamId());
        if (membership != null) {
            return AccessorLevel.teamMember;
        } else {
            return AccessorLevel.publicLevel;
        }
    }

    @GetMapping("/permission/{id}")
    public UserPermission getCurrentUserPermission(@PathVariable Long id) {
        if (!auth.isLoggedIn()) {
            throw new UnauthorizedException();
        }
        var document = documentRepository.findById(id).orElse(null);
        if (document == null) {
            throw new NotFoundException("文档不存在");
        }

        // 获取当前用户与文章的关系：(AccesserLevel)
        var level = getAccessorLevel(document, auth.userId());

        if (level == AccessorLevel.self) {
            return new UserPermission(auth.userId(), Access.ReadWrite, Access.ReadWrite, true);
        }

        if (level == AccessorLevel.teamMember) {
            return new UserPermission(auth.userId(), document.getTeamDocumentAccess(),
                    document.getTeamCommentAccess(), document.getTeamCanShare());
        }

        // public
        return new UserPermission(auth.userId(), document.getPublicDocumentAccess(),
                document.getPublicCommentAccess(), document.getPublicCanShare());
    }
}

enum AccessorLevel {
    publicLevel, teamMember, self
}
