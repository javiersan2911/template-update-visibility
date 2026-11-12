package com.tuv.fanout.port;

import com.tuv.fanout.domain.AttemptId;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;

/** Request to process one engagement for one published version. */
public record ApplyCommand(
        AttemptId attemptId, FirmId firmId, EngagementId engagementId, VersionPin pin) {

    public static ApplyCommand forItem(WorkItem item) {
        return new ApplyCommand(item.attemptId(), item.firmId(), item.engagementId(), item.pin());
    }
}
