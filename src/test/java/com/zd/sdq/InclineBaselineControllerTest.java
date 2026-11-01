package com.zd.sdq;

import com.zd.sdq.controller.InclineBaselineController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InclineBaselineControllerTest {
    @TempDir
    Path directory;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        var database = InclineBaselineTest.database(directory.resolve("devices.db"));
        mvc = MockMvcBuilders.standaloneSetup(
                new InclineBaselineController(database.service(), database.mapper())).build();
    }

    @Test
    void createsReadsUpdatesAndRemovesBaselineThroughApi() throws Exception {
        mvc.perform(put("/api/incline-baselines/TEST-INC").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"angleX\":1.25,\"angleY\":-2.5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.angleX").value(1.25));
        mvc.perform(get("/api/incline-baselines"))
                .andExpect(status().isOk()).andExpect(jsonPath("$['TEST-INC'].angleY").value(-2.5));
        mvc.perform(put("/api/incline-baselines/TEST-INC").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"angleX\":0,\"angleY\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.angleX").value(0.0));
        mvc.perform(delete("/api/incline-baselines/TEST-INC")).andExpect(status().isNoContent());
        mvc.perform(get("/api/incline-baselines")).andExpect(content().json("{}"));
    }

    @Test
    void rejectsMissingDevicesNonInclineDevicesAndIncompleteValues() throws Exception {
        mvc.perform(put("/api/incline-baselines/UNKNOWN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"angleX\":1,\"angleY\":2}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/incline-baselines/TEST-VIB").contentType(MediaType.APPLICATION_JSON)
                .content("{\"angleX\":1,\"angleY\":2}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/incline-baselines/TEST-INC").contentType(MediaType.APPLICATION_JSON)
                .content("{\"angleX\":1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/incline-baselines")).andExpect(content().json("{}"));
    }

}
