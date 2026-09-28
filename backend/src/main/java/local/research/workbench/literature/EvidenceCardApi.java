package local.research.workbench.literature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evidence-cards")
public class EvidenceCardApi {
    public record Card(String id,String projectId,String documentId,String chunkId,String title,String fileName,
                       int pageNumber,String documentSha256,String chunkSha256,String sourceQuote,String sourceUrl,
                       String researchClaim,String dataRequirements,String availabilityNote,
                       String reproductionSteps,String observation,String discrepancy,String status,
                       Instant createdAt,Instant updatedAt,Instant reviewedAt) {}
    public record Create(@NotNull UUID projectId,@NotNull UUID chunkId,
                         @NotBlank @Size(max=1200) String sourceQuote,
                         @NotBlank @Size(max=500) String researchClaim,
                         @Size(max=2000) String dataRequirements,@Size(max=1000) String availabilityNote,
                         @Size(max=4000) String reproductionSteps,@Size(max=4000) String observation,
                         @Size(max=4000) String discrepancy) {}
    public record Update(@NotNull UUID projectId,@NotBlank @Size(max=500) String researchClaim,
                         @Size(max=2000) String dataRequirements,@Size(max=1000) String availabilityNote,
                         @Size(max=4000) String reproductionSteps,@Size(max=4000) String observation,
                         @Size(max=4000) String discrepancy) {}

    private final EvidenceCardService service;
    public EvidenceCardApi(EvidenceCardService service) { this.service=service; }

    @GetMapping public List<Card> list(@RequestParam UUID projectId) { return service.list(projectId.toString()); }
    @PostMapping public Card create(@Valid @RequestBody Create request) { return service.create(request); }
    @PutMapping("/{id}") public Card update(@PathVariable UUID id,@Valid @RequestBody Update request) {
        return service.update(id.toString(),request);
    }
    @PostMapping("/{id}/review") public Card review(@PathVariable UUID id,@RequestParam UUID projectId) {
        return service.setReviewed(id.toString(),projectId.toString(),true);
    }
    @PostMapping("/{id}/reopen") public Card reopen(@PathVariable UUID id,@RequestParam UUID projectId) {
        return service.setReviewed(id.toString(),projectId.toString(),false);
    }
}
