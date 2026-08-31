package org.casemgmt.service;

import java.util.List;
import org.casemgmt.repo.MilestoneRepository;

/** Read access to engine-observed milestone projections. */
public class MilestoneService {

    private final MilestoneRepository milestones;

    public MilestoneService(MilestoneRepository milestones) {
        this.milestones = milestones;
    }

    public List<MilestoneRepository.MilestoneRow> forCase(String caseId) {
        return milestones.findByCase(caseId);
    }
}
