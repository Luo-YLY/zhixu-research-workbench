package local.research.workbench.artifact;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/artifacts")
public class ArtifactApi {
    private final ArtifactStore artifacts;
    public ArtifactApi(ArtifactStore artifacts) { this.artifacts=artifacts; }
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var result=artifacts.download(id.toString());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.name()).build().toString())
                .contentLength(result.bytes().length).body(result.bytes());
    }
}
