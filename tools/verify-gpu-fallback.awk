/\[GPU-FALLBACK-TEST\].*coverageUnchanged=true/ { coverage=1 }
/generation backend=CPU_FALLBACK/ {
    match($0, /completed=[0-9]+/)
    split(substr($0,RSTART,RLENGTH),value,"=")
    completed=value[2]+0
}
/stage timings/ {
    match($0, /readbacks=[0-9]+/)
    split(substr($0,RSTART,RLENGTH),value,"=")
    readbacks=value[2]+0
    if (completed>readbacks && readbacks>=512) verified=1
}
END {
    if (coverage && verified) {
        print "PASS: injected fallback preserves coverage; completed work exceeds actual GPU readbacks"
        exit 0
    }
    print "FAIL: missing injection, GPU readbacks, or subsequent CPU completion evidence"
    exit 1
}
