package com.transitanalytics.zbiorkom;

import com.transitanalytics.dto.VersionDto.Release;
import com.transitanalytics.dto.VersionDto;
import com.transitanalytics.web.VersionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecorderVersionTest {

    private static final Release APP = new Release("1.2.3", "2026-11-24");

    private static VersionDto versions(ResponseCreator answer) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://recorder.test");
        MockRestServiceServer.bindTo(builder).build().expect(requestTo("http://recorder.test/actuator/info"))
                .andRespond(answer);
        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        build.setProperty("released", "2026-11-24");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("build", new BuildProperties(build));
        return new VersionController(beans.getBeanProvider(BuildProperties.class),
                new RecorderClient(builder.build(), JsonMapper.builder().build())).version();
    }

    @Test
    void givesBothVersions() {
        assertThat(versions(withSuccess("{\"build\":{\"version\":\"4.5.6\",\"released\":\"2026-11-20\"}}",
                MediaType.APPLICATION_JSON))).isEqualTo(new VersionDto(APP, new Release("4.5.6", "2026-11-20")));
    }

    @Test
    void leavesOutTheRecordersWhenItDoesntAnswerOrHasNone() {
        assertThat(versions(withServerError())).isEqualTo(new VersionDto(APP, null));
        assertThat(versions(withSuccess("{}", MediaType.APPLICATION_JSON))).isEqualTo(new VersionDto(APP, null));
    }
}
