math.randomseed(os.time())

url = "/v0/entity?id="
headers = {}
headers["Host"] = "localhost:8080"

request = function()
    id = math.random(0, 4000000)
    return wrk.format("GET", tostring(url .. id), headers)
end