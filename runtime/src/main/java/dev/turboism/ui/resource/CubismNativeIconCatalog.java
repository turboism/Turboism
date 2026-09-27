package dev.turboism.ui.resource;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.sdk.ui.resource.CubismIcon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Static evidence only: logical-16 PNGs in the two exact reviewed artifacts, not bundled assets. */
final class CubismNativeIconCatalog {
    private CubismNativeIconCatalog() { }

    static Optional<HostArtifactDigest> artifact(final String version) {
        return switch (version) {
            case ReviewedHostArtifacts.CUBISM_5_2_03_VERSION -> Optional.of(ReviewedHostArtifacts.CUBISM_5_2_03);
            case ReviewedHostArtifacts.CUBISM_5_3_02_VERSION -> Optional.of(ReviewedHostArtifacts.CUBISM_5_3_02);
            default -> Optional.empty();
        };
    }

    // Rows: object, theme, disabled, then SHA-256 for scales 100/125/150/175/200.
    // All eighty PNG hashes and dimensions were independently compared in both pinned JARs.
    private static final String PINS = """
        ART_MESH LIGHT false 149ef063dc6ef431de062a1876592eeaed1940c4d0097c528f62a7d6252c0698 e76aad83969af2b0597714ada1b65817b547def930b377ee23b7ae8b4d0ce151 956783653db3224e20a4fc8574421eb1b1731549427487edb6c579f01efd1df3 f5d8194e751283474b67adbe5c3d26e9c5b9d047850888b1cdfdcb90a2bde5ee fe49aaab0358e638bdbd0399f0667467429bd5a6a231c13c9c555d9156368647
        ART_MESH LIGHT true 9bef98387b111507874d432eaa8a6f0890cd4f625fa504893e751ad88455c14b 12a17c136d2d8abf528515c37a211ba6a44215ddbec5b2eaf4294bc7d8e43507 d23d8932dd81d83df28e2c62febf92b13f17f03f9923919df5ea290b6f0d18c1 7a79f7b1b1f2c43e94344a3e0bffb6c079b2ef6b3edef1043ae23a001cfb0ee7 36fc5b5633ab264637ba2f7b977ccd5d4e08436e77cbbf294a8ff3b9254d3477
        ART_MESH DARK false 1a552f4caffaf9c688821dff69d3d761134e14c06083fdc890b0b1788c171ebe e1a217393ccffb200a47a4ec2b0776c3181263959ada7b38d94c63f2e85a254b 9fd3050522d222f768a6cc4474a6a557deaabde77fb07a6970d8da711bb5c3dc 3510fdfcc15f00da601f2e4dc413c3945ec12c8c4ec7a43282c38fda544b35ec ff648d634dbacaa63a6a66b443cefa81266c74e9fefa7479e2f1b5ebf3b38523
        ART_MESH DARK true e43dc77c4328df227bd2e8101c7f3b736c4af62d9294a56edf673c31533754cf 901f026ae6e0bb4e9c8adaecedf055705a0002a3095b2dcf251be533d2c7bd3a 7b95c06931be7d0e1fb6f862477b8dee9174795082fb9e1dcfbf37870360432f c3bdf9a65ccc14e3b61486a3faca40cc331bc124e04a6318a6787763314a16e5 99ab980ee29fb3f34bbd6199e53ec1e043ee5c2249f70b345778da3b2685d0a1
        WARP_DEFORMER LIGHT false c78263f4b9bc100316cc8e9e40dab69bf06300daec75d3b4f8a2399fd8da7e08 60b89ea569e12a4c1f5aa33386be26789b090671130ff480bde5f12926a90ee2 32a6032f0ce347b9748623a4ccadb08ee44125650d840806dec7ba8b91f39f8c 43884bae0878dadff34a4a71e0a564acc42b5d1ac5a2d7cd59bd6ee0b5d00884 dd5db8acde5f54b117336c1d3ffa7da0ebcbf84f42f9ea53f4638087aee13eba
        WARP_DEFORMER LIGHT true ee40d753ecad97fa62eb319087b7771f75001d841c5631858b0c06f1dced8d90 ee7f252a3fbafb8e63b721d22826fc944a25138c9e2fd19940a8959097819dfd b67b819281349854a699cc6f79163a732269e9178d8e89e7ab314705c3a25113 c2d11e3b8920de47e0aceedf228d3af304246a11a0e67e74f5d1a5aee3016aae 79cb0bf8f4ab5d2ce986eeaa42d047edbf4b891c44b09225f2e2a606782e6e6a
        WARP_DEFORMER DARK false 899e7605865e92fa1d4d871fcf88db4acbacc89fbf2f930706c7f4487084430f d2ed9b2484d7f20bb03bf053019383a8e82f286fc480727aa60265a322e6b38f 2204aa29696e50fef6ec400e638f0ea804749dfb1b5a478111afd80a3cebc8ad 9384d01b9e9c7d08fcab47a216dcc04a723402d7aaa81842dac5c4d674d64136 3e0bf34c9093a336e0353b2826bacc9c235201661c50d8e5d14ff8aca23b7669
        WARP_DEFORMER DARK true fa16ec0e6e37f9696fc9ae5800f94c98f30cff14fd3062df437c972cb29c3c26 6612631f28f73b4ebcd12d74dc636621d96f65965213ca8807436bf428a97dd1 2ce28146dc248b38c14bb02d2a71dddb309def6d83191b5a8a4c1c53d14e24fd f21b375147bdaf66bd4070acf0094bd32556a2efd40b5ba916e4be54ba0a80f6 9d526739aa18ebc87cb9cfa8d9f02ae3cc9b9652ae8fe91495d4c4ac0ab68db2
        ROTATION_DEFORMER LIGHT false d51bad5f01aa7866dc6f5d41cca35224628433f8601eb17e42cdd713538bad39 cda7057b0e1c010b8633bd89ab6d03be8b82b503962f9794c554c955e676b79b c7ac8753d40bbbaf130e18e55aaa5ddf34748da7b211842b6c417108dfeb33f0 a026079761c227c1fb767fa163ced85e166a691708c4ee287bbf10cfc7027df7 30f2ef1bcb66fee378b8c2a42b583a94ebcfee9c045d79874eda86db363105d6
        ROTATION_DEFORMER LIGHT true 9caf75ed040f6f2cc61335f2a0d94ead6b02e0f939cbc9499d84aed5e1ad050f 04f6bb1245a268ebd62f4fe955303386c029e17a02b9abd18b339f6fe5484a99 bea52810c22f0f7cc151ee5f60ba884d4d243e2363e8898a649e004a0171fd9e 91a882e85ff82cc510373d1a5669d452146d714f66a47b12fc9f82d0dec9ee61 3a4eb7e40ed68d192cf7a37e3b55a096c822e8f5716347954304947464c50b49
        ROTATION_DEFORMER DARK false c5c7af57174185c2be76530d8243429bd2c4f2e2f82b3c7a40894f04feda5930 2ac686007c85769640b83f8b8c1c6a1918843f743a5cb2366298239d2dc7936d e2d877c439d5702bd0930ce015bc65fc1c696079daf3b4f028bb7ef83dfd45b6 81b65833a0dc8b449f532786f4306d2da49f0db8b819be25cdcf2859d9d3a221 25cb35a2ba336e3dd89bea46941172fe93a2589b1fbd6d0438ec8fcdfcb50bb6
        ROTATION_DEFORMER DARK true fdf7e8b718639e393f6c688c364761d0772968848976870301f0ff5802b17918 28d3a9e0d6b0696cfa43673ae52ccb644a60863e88eed9036b9c4ba5c3016c00 a608e29aaf09c61fa64d98f95814f163f2a2bd44581537b63ef37570c8a21ff7 559cac12ae2de218e28d9e0432238c0732d17d76113bb5c86873cf0435c6330f 5e8c23d5246a3c866278b57e50dc82c1a966c16277036bde1c1f1eb35a18992b
        PART LIGHT false c4971cbe7a224d4d7af820ed05ce2f527df832ce96992fd48516c863c3239700 27c8932466d3974a7e5dff614ef8dbb477998ab8bf3a165b07ad884b9d8fbd40 f937ead4da36c8a2581a365694c73b9431574e2df4da0e346cac8e8fc90e55f0 c5f30040b0cef25779eaf5629ce132ca1f908859c4c52e247c6aef37d25e74ce 0878fd76b24eb79ee7fd4360dc194fc82e4e0db029918c6e4d60ad6d7541e7e2
        PART LIGHT true e21e07a448c22856a65c2cdfb6470ceece9d2ca8a740442a688af776f96de2f4 b9d3cc1aaa03afa5d342a9ad6eb65fe4e7986a45e7ce5197b902ca4b7fe6d40d df4a341f494bb65af57362df9a49709fc2a30862c5e9679778779f1fe3c7f806 09dcbe1350a1fd91e0896f5286cc951b7e8e768b88cb465f24c4ba9d67c30fbe fd6bb2f616b70ce51241df1fff6226845a1b5e81b75dfb9748be157086beecf0
        PART DARK false 359e1cb7e59efeefb2c7e8adc37ca126d5605f194ceb5a6e17fddbf5b8c7c930 0f7f787aeb79e311103d2214e725b00f8f2ce301b9427f689f247a666f9d5912 c66dfc4acbd731b606e3cf249a4ba68c7ae97e190be503b248b1e8e66cde0ef8 1afaa635fce9d46e91f85b5bc522b4764554838e06e8c9ca381b7beeeaff5300 8c8b6b346b7d253a383266d3e06bd3d12d1bd433ff64ee1ccfbf05e048ecb81c
        PART DARK true ea5b752d76d57c64b61e8761bda97d588907cc193ce9856ab02b46a56238b79e cfce2af3256a577183297b65d94c6cbf36d22669d765141a7c21d39d0f9752dc f566ce4d052ca2cee254a551ae907a27fdf09f91e8d52f0cea8fc175f5df9849 69f0680c7e448b0587ea43aaebccb13b70a1582a51b9fb2ad669ad0117a6501a 5e8e126a7d1c74ac27a564bde71afbd3436610569d16b841da4aab361a3c1043
        """;
    private static final Map<NativeIconVariant, String> RESOURCES = parsePins();

    static Map<NativeIconVariant, String> resources() {
        return RESOURCES;
    }

    private static Map<NativeIconVariant, String> parsePins() {
        final Map<NativeIconVariant, String> result = new LinkedHashMap<>();
        for (String row : PINS.strip().split("\\R")) {
            final String[] cells = row.strip().split(" +");
            if (cells.length != 8 || !(cells[2].equals("true") || cells[2].equals("false"))) {
                throw new IllegalStateException("invalid native icon catalog row");
            }
            for (int index = 0; index < 5; index++) {
                final NativeIconVariant key = new NativeIconVariant(CubismIcon.valueOf(cells[0]),
                    NativeIconVariant.Theme.valueOf(cells[1]), 100 + index * 25, Boolean.parseBoolean(cells[2]));
                final String hash = cells[index + 3];
                if (!hash.matches("[0-9a-f]{64}") || result.putIfAbsent(key, hash) != null) {
                    throw new IllegalStateException("invalid or duplicate native icon pin");
                }
            }
        }
        if (result.size() != 80) throw new IllegalStateException("incomplete native icon catalog");
        return Map.copyOf(result);
    }
}
