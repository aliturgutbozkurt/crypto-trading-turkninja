package com.turkninja.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardWebController {

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("title", "TurkNinja Trading Bot - Web Dashboard");
        return "dashboard";
    }
}
