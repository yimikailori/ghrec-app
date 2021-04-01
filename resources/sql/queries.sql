--

-- :name proc-get-sweep-candidates :? :*
-- :doc get sweep candidates
select * from proc_get_sweep_candidates(:batch_size::int);


-- :name proc-update-last-recovery-attempt :? :1
-- :doc update last recovery attempt
select * from proc_update_last_recovery_attempt(:loan_id::bigint);


-- :name proc-get-recovery-candidates :? :*
-- :doc get recovery candidates
select * from proc_get_recovery_candidates(:sub::bigint,:time::timestamp);

-- :name get-loanbalance :? :1
-- :doc reconfirm loan balance
select cedis_loaned - cedis_paid as outstanding
from tbl_loans_new where loan_id = :loan_id
and not exists (select * from tbl_log_recovery_new
where recovery_code = -1 and loan_fk = :loan_id);

--:name insert-recovery-attempt :? :1
--:doc insert attempt to recover into db
insert into tbl_log_recovery_new (recovery_method,request_id, loan_fk, subscriber_fk, cedis_balance,
								recovery_code, amount_requested)
								values (:recovery_method,:request_id::bigint,:loan_fk,:subscriber,:cedis_balance,
								        -1,:amount_requested);
--:name proc-update-recovery-status :? :1
--:doc update recovery attempt
select * from proc_update_recovery_status(:loan_id::bigint, :request_id::bigint, :cedis_recovered::int,:recovery_code::int,
                                         :error_message::text);

