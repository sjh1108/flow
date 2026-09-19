package com.flow.extguard.upload.repository;

import com.flow.extguard.upload.domain.UploadRecord;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface UploadRecordRepository extends Repository<UploadRecord, Long> {

    UploadRecord save(UploadRecord uploadRecord);

    List<UploadRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
