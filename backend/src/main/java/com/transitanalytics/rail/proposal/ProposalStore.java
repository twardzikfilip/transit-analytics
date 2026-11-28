package com.transitanalytics.rail.proposal;

import com.transitanalytics.dto.CityRailProposalDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The proposal worked out, stored a row per solution and frequency ({@code city_rail_proposal_variant}). */
@Component
public class ProposalStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Which railways trains run on today, and the rules: the same for every variant. */
    record Meta(CityRailProposalDto.Rules rules, List<String> served) {
    }

    /** A proposal: the weekday, its meta, and each solution's variants in order (none without trains). */
    record Stored(LocalDate day, Meta meta, Map<String, List<CityRailProposalDto.Variant>> variants) {
    }

    private record Row(String solution, String frequency, LocalDate day, String meta, String variant) {
    }

    private final JdbcTemplate jdbc;

    public ProposalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The proposal stored for this network; empty when there is none, or not all of it. */
    Optional<Stored> load(String networkHash, List<String> solutions, List<String> frequencies) {
        List<Row> rows = jdbc.query("""
                SELECT solution, frequency, day, meta::text AS meta, variant::text AS variant
                FROM city_rail_proposal_variant WHERE network_hash = ?""",
                (rs, i) -> new Row(rs.getString("solution"), rs.getString("frequency"),
                        rs.getObject("day", LocalDate.class), rs.getString("meta"), rs.getString("variant")),
                networkHash);
        if (rows.size() != solutions.size() * frequencies.size()) {
            return Optional.empty();
        }
        Map<String, List<CityRailProposalDto.Variant>> variants = new LinkedHashMap<>();
        for (String id : solutions) {
            List<CityRailProposalDto.Variant> list = new ArrayList<>();
            for (String f : frequencies) {
                rows.stream().filter(r -> r.solution().equals(id) && r.frequency().equals(f)).findFirst()
                        .ifPresent(r -> list.add(JSON.readValue(r.variant(), CityRailProposalDto.Variant.class)));
            }
            variants.put(id, list);
        }
        return Optional.of(new Stored(rows.get(0).day(), JSON.readValue(rows.get(0).meta(), Meta.class), variants));
    }

    /**
     * Stores a proposal in place of any for another network. Rows are written one by one: an interrupted write leaves
     * too few, and the proposal is worked out again.
     */
    void save(String networkHash, Stored proposal, Instant computedAt) {
        jdbc.update("DELETE FROM city_rail_proposal_variant");
        String meta = JSON.writeValueAsString(proposal.meta());
        Timestamp at = Timestamp.from(computedAt);
        proposal.variants().forEach((solution, variants) -> variants.forEach(variant -> jdbc.update("""
                INSERT INTO city_rail_proposal_variant
                    (solution, frequency, network_hash, day, computed_at, meta, variant)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""", solution, variant.frequency(), networkHash,
                proposal.day(), at, meta, JSON.writeValueAsString(variant))));
    }
}
