package fullforum.controllers;

import fullforum.data.models.Document;
import fullforum.data.repos.DocumentRepository;
import fullforum.dto.in.CreateDocumentModel;
import fullforum.dto.in.DocumentTestModel;
import fullforum.dto.in.PatchDocumentModel;
import fullforum.dto.out.IdDto;
import fullforum.services.Snowflake;
import org.hibernate.cfg.NotYetImplementedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documents")
@Validated// PathVariable and params auto validation
public class DocumentController {
    @Autowired
    Snowflake snowflake;

    @Autowired
    DocumentRepository documentRepository;

    @PostMapping("update-doc-1")
    public void updateDocumentOne(@RequestBody DocumentTestModel model) {
        var doc = documentRepository.findById(1L).orElse(null);
        if (doc == null) {
            doc = new Document(1);
        }

        doc.setData(model.data, 1L);

        documentRepository.save(doc);
    }

    @GetMapping("get-doc-1")
    public Document getDocumentOne() {
        var doc = documentRepository.findById(1L).orElse(null);
        return doc;
    }

    @PostMapping
    public IdDto createDocument(@RequestBody CreateDocumentModel model) {
        throw new NotYetImplementedException();
    }

    @PatchMapping
    public void patchDocument(@RequestBody PatchDocumentModel model) {
        throw new NotYetImplementedException();
    }

    @DeleteMapping("{id}")
    public void removeDocument(@PathVariable Long id) {
        throw new NotYetImplementedException();
    }
}
