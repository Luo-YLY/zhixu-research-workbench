package local.research.workbench.literature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/literature")
public class LiteratureApi {
    public record Document(String id,String projectId,String title,String fileName,String mediaType,
                           String sha256,long sizeBytes,int pageCount,int chunkCount,Instant createdAt) {}
    public record Hit(String chunkId,String documentId,String title,String fileName,int pageNumber,int chunkNumber,
                      String documentSha256,String chunkSha256,String excerpt,double score,String sourceUrl) {}
    public record SearchResult(String query,String retrievalVersion,List<Hit> hits) {}
    public record AskRequest(@NotNull UUID projectId,UUID documentId,@NotBlank @Size(max=1000) String question) {}
    public record Answer(String status,String answer,String retrievalVersion,List<Hit> citations) {}

    private final LiteratureService service;
    public LiteratureApi(LiteratureService service) { this.service=service; }

    @GetMapping("/documents")
    public List<Document> documents(@RequestParam UUID projectId) { return service.documents(projectId.toString()); }

    @PostMapping(path="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document upload(@RequestParam UUID projectId,@RequestParam String title,@RequestPart("file") MultipartFile file) {
        return service.upload(projectId.toString(),title,file);
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        var source=service.file(id.toString());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(source.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(source.fileName()).build().toString())
                .contentLength(source.bytes().length).body(source.bytes());
    }

    @GetMapping("/search")
    public SearchResult search(@RequestParam UUID projectId,@RequestParam String q,
                               @RequestParam(required=false) UUID documentId,@RequestParam(defaultValue="5") int limit) {
        return service.search(projectId.toString(),documentId==null?null:documentId.toString(),q,limit);
    }

    @PostMapping("/answer")
    public Answer answer(@Valid @RequestBody AskRequest request) {
        return service.answer(request.projectId().toString(),
                request.documentId()==null?null:request.documentId().toString(),request.question());
    }
}
