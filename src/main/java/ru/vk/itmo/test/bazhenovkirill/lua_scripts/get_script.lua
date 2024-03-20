id = 0
path = "/v0/entity?id="

function generate_random_key()
    return "key" .. id
end

function request()
    id = id + 1
    url = path .. generate_random_key()
    wrk.headers["Host"] = "localhost:8080"
    return wrk.format("GET", url, headers)
end