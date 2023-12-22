package fr.graynaud.journeymapspring;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

@Controller
@AllArgsConstructor
@Slf4j
public class IndexController {

    private final JourneyMapService journeyMapService;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT);

    @GetMapping()
    public String register(Model model, Locale locale) {
        try {
            model.addAttribute("modificationDate",
                               this.journeyMapService.getLastModif().map(t -> DATE_TIME_FORMATTER.withLocale(locale).format(t)).orElse("Jamais"));
        } catch (IOException e) {
            model.addAttribute("error", true);
            log.error(e.getMessage(), e);
        }

        return "index";
    }

}
