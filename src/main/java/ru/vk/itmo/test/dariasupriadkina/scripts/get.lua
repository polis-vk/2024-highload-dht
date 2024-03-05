id = 0
url = "/v0/entity?id="
headers = {}
headers["Host"] = "localhost:8080"

request = function()
    id = id + 1
    return wrk.format("GET", url .. id, headers)
end