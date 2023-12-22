package fr.graynaud.journeymapspring;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@AllArgsConstructor
@Slf4j
public class MergeController {

    private final JourneyMapService journeyMapService;

    @PostMapping(value = "/merge")
    public String postRegister(@RequestParam("file") MultipartFile file) {
        try {
            this.journeyMapService.merge(file);
        } catch (BadRequestException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return "error";
        }

        return "ok";
    }

}
