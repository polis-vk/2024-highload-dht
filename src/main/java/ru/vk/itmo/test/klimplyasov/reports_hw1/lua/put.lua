id_value = 0
function request()
    path = "/v0/entity?id=" .. id_value
    local body = "body$" .. id_value
    headers = {}
    headers["Host"] = "localhost:8080"
    id_value = id_value + 1
    return wrk.format("PUT", path, headers, body)
end