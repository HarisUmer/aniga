package com.aniga.backend.people;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.auth.AuthFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

    public record AddRequest(String username) {
    }

    private final PeopleService people;

    public PeopleController(PeopleService people) {
        this.people = people;
    }

    @GetMapping("/friends")
    public List<PeopleService.Person> friends(HttpServletRequest request) {
        return people.friends(AuthFilter.userId(request));
    }

    @GetMapping("/requests")
    public List<PeopleService.RequestView> requests(HttpServletRequest request) {
        return people.incoming(AuthFilter.userId(request));
    }

    @GetMapping("/requests/outgoing")
    public List<PeopleService.RequestView> outgoing(HttpServletRequest request) {
        return people.outgoing(AuthFilter.userId(request));
    }

    @GetMapping("/search")
    public List<PeopleService.Person> search(HttpServletRequest request, @RequestParam String q) {
        return people.search(AuthFilter.userId(request), q);
    }

    @PostMapping("/requests")
    public PeopleService.Outcome add(HttpServletRequest request, @RequestBody AddRequest body) {
        return people.request(AuthFilter.userId(request), body.username());
    }

    @PostMapping("/requests/{id}/accept")
    public PeopleService.Outcome accept(HttpServletRequest request, @PathVariable long id) {
        return people.accept(AuthFilter.userId(request), id);
    }

    @PostMapping("/requests/{id}/decline")
    public PeopleService.Outcome decline(HttpServletRequest request, @PathVariable long id) {
        return people.decline(AuthFilter.userId(request), id);
    }

    @GetMapping("/matches")
    public List<PeopleService.Match> matches(HttpServletRequest request) {
        return people.matches(AuthFilter.userId(request));
    }

    @GetMapping("/personality")
    public PeopleService.Personality personality(HttpServletRequest request) {
        return people.personality(AuthFilter.userId(request));
    }

    @GetMapping("/{id}/shelf")
    public List<PeopleService.ShelfItem> shelf(HttpServletRequest request, @PathVariable long id) {
        return people.shelf(AuthFilter.userId(request), id);
    }
}
