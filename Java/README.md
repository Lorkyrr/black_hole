# Black hole em Java (Vulkan 1.3, LWJGL)

Mesmo desenho do port C++/Vulkan: compute shader de geodésicas -> imagem 200x150 -> quad de tela cheia, com a grade de espaço-tempo por baixo.
Usa *dynamic rendering* e *synchronization2* (Vulkan 1.3), sem render pass nem framebuffers. Os bindings vêm do [LWJGL 3](https://www.lwjgl.org/).
Os shaders GLSL (`src/main/resources/shaders/`) são iguais aos de `C++/` e são compilados para SPIR-V ao iniciar, com shaderc (não precisa de `glslc`).

Documentação: [`PORT_JAVA.md`](PORT_JAVA.md) (o que foi feito e por quê) e [`TUTORIAL_JAVA.md`](TUTORIAL_JAVA.md) (passo a passo).
Código gerado com ajuda de IA (Claude Code), como base de estudo para aprender Vulkan e Java.

## Dependências (Debian/Ubuntu)

    sudo apt install openjdk-25-jdk maven mesa-vulkan-drivers libwayland-client0 libdecor-0-0

Java 25 ou mais novo, e uma GPU com driver Vulkan 1.3 (`vulkaninfo --summary`). As bibliotecas Java (LWJGL, JOML, JUnit) o Maven baixa sozinho.

## Rodar

    mvn package                              # compila, roda os testes e gera target/black_hole_java.jar
    java -jar target/black_hole_java.jar     # janela
    mvn test                                 # física e câmera (sem GPU)
    cp target/black_hole_java.jar ../Java.jar   # jar na raiz (ignorado pelo git)

Para ligar a validation layer (se `vulkan-validationlayers` estiver instalado): `java -Dvk.validation=true -jar ...`.

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade, Esc = sair.

Só Linux x64: o `pom.xml` usa `natives-linux`. Em outro sistema, troque a propriedade `lwjgl.natives`.
