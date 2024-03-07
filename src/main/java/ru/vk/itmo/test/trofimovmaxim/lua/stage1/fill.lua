key = 0
function request()
    path = "/v0/entity?id=" .. key
    key = key + 1
    return wrk.format("PUT", path, {}, string.rep(tostring(key - 1), 100))
end
