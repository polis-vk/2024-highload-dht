base_url = "/v0/entity?"
headers = {}
headers["Host"] = "localhost:8080"
wrk.port=8080
function build_query_param(param, value)
    return param.."="..value
end

function getOneRandom()
    return wrk.format("GET", base_url..build_query_param("id", math.random(0, 100000)), headers)
end


function putOneRandom()
    return wrk.format("PUT", base_url..build_query_param("id", math.random(0, 100000)), headers, math.random(0, 10000))
end


function deleteOneRandom()
    return wrk.format("DELETE", base_url..build_query_param("id", math.random(0, 100000)), headers)
end


request = function()
--    magic_selector = math.random(0,3)
--    if magic_selector < 2 then
--        return getOneRandom()
--    elseif magic_selector == 3 then
--        return putOneRandom()
--    else
--        return deleteOneRandom()
--    end
--    return putOneRandom()
end

--при тестировании нужные методы просто раскоментировал, хотя и в полной имплементации тоже тестировал
