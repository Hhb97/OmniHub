package com.omnihub.iot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Small shared helpers: paging limits, response envelope, JSON helper and business exceptions. */
class CommonHelpersTest {

    // ------------------------------------------------------------------ PageQuery

    @Test
    void pageSizeDefaultsAndIsCapped() {
        assertThat(PageQuery.size(null)).isEqualTo(10);
        assertThat(PageQuery.size(0)).isEqualTo(10);
        assertThat(PageQuery.size(-5)).isEqualTo(10);
        assertThat(PageQuery.size(1)).isEqualTo(1);
        assertThat(PageQuery.size(25)).isEqualTo(25);
        assertThat(PageQuery.size(100)).isEqualTo(100);
        assertThat(PageQuery.size(101)).isEqualTo(100);
        assertThat(PageQuery.size(Integer.MAX_VALUE)).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void pageNumberStartsAtOne() {
        assertThat(PageQuery.page(null)).isEqualTo(1);
        assertThat(PageQuery.page(0)).isEqualTo(1);
        assertThat(PageQuery.page(-3)).isEqualTo(1);
        assertThat(PageQuery.page(7)).isEqualTo(7);
    }

    @Test
    void pageQueryBuildsAMybatisPlusPage() {
        Page<Object> p = PageQuery.of(3, 500);
        assertThat(p.getCurrent()).isEqualTo(3);
        assertThat(p.getSize()).isEqualTo(100);
    }

    // ------------------------------------------------------------------ ApiResponse / PageResult

    @Test
    void successEnvelopeHasCodeZero() {
        ApiResponse<String> r = ApiResponse.ok("x");
        assertThat(r.code()).isZero();
        assertThat(r.message()).isEqualTo("ok");
        assertThat(r.data()).isEqualTo("x");
        assertThat(ApiResponse.ok().data()).isNull();
    }

    @Test
    void errorEnvelopeCarriesCodeMessageAndDetails() {
        ApiResponse<Map<String, String>> r = ApiResponse.error(400, "bad", Map.of("name", "required"));
        assertThat(r.code()).isEqualTo(400);
        assertThat(r.message()).isEqualTo("bad");
        assertThat(r.data()).containsEntry("name", "required");
    }

    @Test
    void pageResultMapsRecordsAndKeepsPagingInfo() {
        Page<Integer> page = new Page<>(2, 3, 11);
        page.setRecords(List.of(4, 5, 6));

        PageResult<String> r = PageResult.of(page, i -> "n" + i);

        assertThat(r.records()).containsExactly("n4", "n5", "n6");
        assertThat(r.total()).isEqualTo(11);
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ BizException

    @Test
    void bizExceptionFactoriesUseTheRightStatus() {
        assertThat(BizException.badRequest("m").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(BizException.notFound("m").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(BizException.conflict("m").getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(BizException.forbidden("m").getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(BizException.conflict("设备离线").getMessage()).isEqualTo("设备离线");
    }

    // ------------------------------------------------------------------ Jsons

    @Test
    void instantsAreWrittenAsIsoStringsNotNumbers() {
        assertThat(Jsons.toJson(Map.of("at", Instant.parse("2026-01-01T08:00:00Z"))))
                .isEqualTo("{\"at\":\"2026-01-01T08:00:00Z\"}");
    }

    @Test
    void parseHandlesNullAndRejectsInvalidJson() {
        assertThat(Jsons.parse((String) null)).isNull();
        assertThat(Jsons.parse("{\"a\":1}").get("a").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> Jsons.parse("{not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jsons.parse("{not json", Map.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseIntoATypeAndRoundTrip() {
        record Point(int x, String label) {
        }
        Point p = Jsons.parse(Jsons.toJson(new Point(3, "a")), Point.class);
        assertThat(p).isEqualTo(new Point(3, "a"));
        assertThat(Jsons.parse(null, Point.class)).isNull();
    }
}
